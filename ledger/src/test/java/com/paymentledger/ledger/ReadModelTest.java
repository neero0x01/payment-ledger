package com.paymentledger.ledger;

import static com.paymentledger.ledger.ReadEndpoints.accounts;
import static com.paymentledger.ledger.ReadEndpoints.statementLines;
import static com.paymentledger.ledger.ReadEndpoints.statementPage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.TransactionPostedProducer.EventEntry;
import com.paymentledger.ledger.TransactionPostedProducer.TransactionPosted;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/**
 * Scenario 10, Read Model half [M5]: the projector builds the Read Model from the TransactionPosted
 * events on Kafka, and the views module serves Account lists and Statements from it. Events are put
 * on Kafka directly, so they can arrive twice or out of order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({
  TestcontainersConfiguration.class,
  ProjectorConfiguration.class,
  LedgerFixture.class,
  Sessions.class,
  TransactionPostedProducer.class
})
class ReadModelTest {

  private static final Duration PROJECTION = Duration.ofSeconds(30);
  private static final Instant HOUR_ZERO = Instant.parse("2026-11-01T00:00:00Z");

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired TransactionPostedProducer events;

  /** An Account the ledger doesn't know, standing in for Funding on the other side. */
  private final AccountId counterparty = new AccountId(UUID.randomUUID());

  @Test
  void posted_transaction_shows_in_the_account_list_and_the_statement() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(0), Money.ofPence(50_00));
    RestTestClient customer = sessions.customerOwning(a);
    Instant bookingTime = Instant.parse("2026-11-20T10:15:30.123456Z");
    TransactionPosted funding =
        TransactionPosted.funding(
            bookingTime,
            new EventEntry(counterparty, Side.DEBIT, 100_00, 1, 100_00),
            new EventEntry(a, Side.CREDIT, 100_00, 1, 100_00));

    events.send(funding);

    List<JsonNode> lines = awaitStatementLines(customer, a, 1);
    JsonNode line = lines.getFirst();
    assertThat(line.get("transactionId").asString())
        .isEqualTo(funding.transaction().value().toString());
    assertThat(Instant.parse(line.get("bookingTime").asString())).isEqualTo(bookingTime);
    assertThat(line.get("side").asString()).isEqualTo("CREDIT");
    assertThat(line.get("amountPence").asLong()).isEqualTo(100_00);
    assertThat(line.get("balanceAfterPence").asLong()).isEqualTo(100_00);
    assertThat(line.get("counterpartyAccountId").asString())
        .isEqualTo(counterparty.value().toString());
    JsonNode account = onlyAccount(accounts(customer));
    assertThat(account.get("id").asString()).isEqualTo(a.value().toString());
    assertThat(account.get("name").asString()).isEqualTo("Main");
    assertThat(account.get("balancePence").asLong()).isEqualTo(100_00);
    assertThat(account.get("overdraftLimitPence").asLong()).isEqualTo(50_00);
    assertThat(account.get("availableBalancePence").asLong()).isEqualTo(150_00);
  }

  @Test
  void event_delivered_twice_is_applied_once() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient customer = sessions.customerOwning(a);
    TransactionPosted first =
        TransactionPosted.funding(
            Instant.parse("2026-11-20T10:00:00Z"),
            new EventEntry(counterparty, Side.DEBIT, 100_00, 1, 100_00),
            new EventEntry(a, Side.CREDIT, 100_00, 1, 100_00));
    TransactionPosted second =
        TransactionPosted.funding(
            Instant.parse("2026-11-20T11:00:00Z"),
            new EventEntry(counterparty, Side.DEBIT, 20_00, 2, 120_00),
            new EventEntry(a, Side.CREDIT, 20_00, 2, 120_00));

    // As when the outbox publisher crashes after sending but before marking the row published.
    events.sendToOnePartition(first, first, second);

    List<JsonNode> lines =
        await()
            .atMost(PROJECTION)
            .until(
                () -> statementLines(customer, a),
                l -> l.stream().anyMatch(line -> isLineOf(line, second)));
    assertThat(lines)
        .extracting(line -> line.get("transactionId").asString())
        .containsExactly(
            second.transaction().value().toString(), first.transaction().value().toString());
    assertThat(onlyAccount(accounts(customer)).get("balancePence").asLong()).isEqualTo(120_00);
  }

  @Test
  void events_out_of_order_leave_the_balance_after_the_highest_entry_seq() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient customer = sessions.customerOwning(a);
    TransactionPosted first =
        TransactionPosted.funding(
            Instant.parse("2026-11-20T10:00:00Z"),
            new EventEntry(counterparty, Side.DEBIT, 100_00, 1, 100_00),
            new EventEntry(a, Side.CREDIT, 100_00, 1, 100_00));
    TransactionPosted second =
        TransactionPosted.funding(
            Instant.parse("2026-11-20T11:00:00Z"),
            new EventEntry(counterparty, Side.DEBIT, 20_00, 2, 120_00),
            new EventEntry(a, Side.CREDIT, 20_00, 2, 120_00));
    TransactionPosted third =
        TransactionPosted.funding(
            Instant.parse("2026-11-20T12:00:00Z"),
            new EventEntry(counterparty, Side.DEBIT, 3_00, 3, 123_00),
            new EventEntry(a, Side.CREDIT, 3_00, 3, 123_00));

    events.sendToOnePartition(third, first, second);

    List<JsonNode> lines = awaitStatementLines(customer, a, 3);
    assertThat(lines).extracting(line -> line.get("entrySeq").asLong()).containsExactly(3L, 2L, 1L);
    assertThat(onlyAccount(accounts(customer)).get("balancePence").asLong()).isEqualTo(123_00);
  }

  @Test
  void statement_pages_newest_first_by_booking_time_and_entry_seq() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient customer = sessions.customerOwning(a);
    events.send(hourlyFundings(a, 55));
    awaitStatementLines(customer, a, 55);

    JsonNode first = statementPage(customer, a);
    JsonNode second = statementPage(customer, a, "cursor", first.get("nextCursor").asString());

    assertThat(entrySeqs(first)).hasSize(50).startsWith(55L, 54L).endsWith(6L);
    assertThat(entrySeqs(second)).containsExactly(5L, 4L, 3L, 2L, 1L);
    assertThat(second.get("nextCursor").isNull()).isTrue();
  }

  @Test
  void statement_filters_by_booking_time_from_inclusive_to_exclusive() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient customer = sessions.customerOwning(a);
    events.send(hourlyFundings(a, 30));
    awaitStatementLines(customer, a, 30);

    JsonNode page =
        statementPage(
            customer,
            a,
            "from",
            HOUR_ZERO.plus(Duration.ofHours(11)).toString(),
            "to",
            HOUR_ZERO.plus(Duration.ofHours(21)).toString());

    assertThat(entrySeqs(page)).containsExactly(20L, 19L, 18L, 17L, 16L, 15L, 14L, 13L, 12L, 11L);
    assertThat(page.get("nextCursor").isNull()).isTrue();
  }

  @Test
  void statement_from_after_to_is_refused() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient customer = sessions.customerOwning(a);

    customer
        .get()
        .uri(
            "/v1/accounts/{id}/statement?from={from}&to={to}",
            a.value(),
            "2026-11-21T00:00:00Z",
            "2026-11-20T00:00:00Z")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/validation-failed");
  }

  @Test
  void customer_sees_only_their_own_accounts_and_an_operator_sees_every_account() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(0));
    AccountId other = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient customer = sessions.customerOwning(a);
    RestTestClient operator = sessions.operator();

    assertThat(onlyAccount(accounts(customer)).get("id").asString())
        .isEqualTo(a.value().toString());
    assertThat(accounts(operator))
        .extracting(account -> account.get("id").asString())
        .contains(
            a.value().toString(), other.value().toString(), ledger.funding().value().toString());
  }

  @Test
  void customer_cannot_read_another_customers_statement() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(0));
    AccountId other = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient customer = sessions.customerOwning(a);

    customer
        .get()
        .uri("/v1/accounts/{id}/statement", other.value())
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/forbidden");
  }

  /**
   * Fundings of the Account of £1.00 each, the first booked at {@link #HOUR_ZERO} plus one hour
   * with entry_seq 1, and each later one an hour later.
   */
  private TransactionPosted[] hourlyFundings(AccountId account, int count) {
    TransactionPosted[] fundings = new TransactionPosted[count];
    for (int seq = 1; seq <= count; seq++) {
      fundings[seq - 1] =
          TransactionPosted.funding(
              HOUR_ZERO.plus(Duration.ofHours(seq)),
              new EventEntry(counterparty, Side.DEBIT, 1_00, seq, seq * 1_00L),
              new EventEntry(account, Side.CREDIT, 1_00, seq, seq * 1_00L));
    }
    return fundings;
  }

  private static List<Long> entrySeqs(JsonNode page) {
    List<Long> seqs = new ArrayList<>();
    page.get("lines").forEach(line -> seqs.add(line.get("entrySeq").asLong()));
    return seqs;
  }

  private static boolean isLineOf(JsonNode line, TransactionPosted event) {
    return line.get("transactionId").asString().equals(event.transaction().value().toString());
  }

  private static List<JsonNode> awaitStatementLines(
      RestTestClient client, AccountId account, int count) {
    return await()
        .atMost(PROJECTION)
        .until(() -> statementLines(client, account), lines -> lines.size() >= count);
  }

  private static JsonNode onlyAccount(JsonNode accounts) {
    assertThat(accounts).hasSize(1);
    return accounts.get(0);
  }
}
