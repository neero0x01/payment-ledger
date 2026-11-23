package com.paymentledger.ledger;

import static com.paymentledger.ledger.ReadEndpoints.accounts;
import static com.paymentledger.ledger.ReadEndpoints.statementLines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.TransactionPostedProducer.EventEntry;
import com.paymentledger.ledger.TransactionPostedProducer.TransactionPosted;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import java.time.Duration;
import java.time.Instant;
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
