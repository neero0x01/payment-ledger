package com.paymentledger.ledger;

import static com.paymentledger.ledger.ReadEndpoints.statementLines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.paymentledger.ledger.TransactionPostedProducer.EventEntry;
import com.paymentledger.ledger.TransactionPostedProducer.TransactionPosted;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import com.paymentledger.ledger.shared.TransactionId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * The consumer side of the {@code ledger.transaction-posted.v1} contract: the events the projector
 * is tested with are the ones {@code docs/schemas/transaction-posted.v1.json} allows, and the
 * projector applies every shape of event it allows. TransactionPostedContractTest is the producer
 * side.
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
class TransactionPostedConsumerContractTest {

  private static final Path SCHEMA = Path.of("../docs/schemas/transaction-posted.v1.json");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant BOOKING_TIME = Instant.parse("2026-11-20T10:15:30.123456Z");

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired TransactionPostedProducer events;

  @Test
  void events_the_projector_is_tested_with_match_the_schema() throws IOException {
    AccountId a = new AccountId(UUID.randomUUID());
    AccountId b = new AccountId(UUID.randomUUID());
    TransactionPosted funding =
        TransactionPosted.funding(
            BOOKING_TIME,
            new EventEntry(b, Side.DEBIT, 100_00, 1, 100_00),
            new EventEntry(a, Side.CREDIT, 100_00, 1, 100_00));

    for (TransactionPosted event :
        List.of(funding, payment(a, b, List.of()), correctionReversing(funding))) {
      assertThat(schemaErrors(event.json())).as(event.kind()).isEmpty();
    }
  }

  @Test
  void projector_applies_a_payment_with_more_than_two_entries() throws IOException {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient customer = sessions.customerOwning(a);
    AccountId x = new AccountId(UUID.randomUUID());
    AccountId y = new AccountId(UUID.randomUUID());
    TransactionPosted payment =
        payment(
            x,
            y,
            List.of(
                new EventEntry(x, Side.DEBIT, 60_00, 1, 60_00),
                new EventEntry(y, Side.DEBIT, 40_00, 1, 40_00),
                new EventEntry(a, Side.CREDIT, 100_00, 1, 100_00)));
    assertThat(schemaErrors(payment.json())).isEmpty();

    events.send(payment);

    JsonNode line =
        await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> statementLines(customer, a), lines -> !lines.isEmpty())
            .getFirst();
    assertThat(line.get("transactionId").asString())
        .isEqualTo(payment.transaction().value().toString());
    assertThat(line.get("balanceAfterPence").asLong()).isEqualTo(100_00);
    assertThat(line.get("counterpartyAccountId").isNull()).isTrue();
  }

  /** A PAYMENT Transaction with a Payment id, with the Entries given or from a to b. */
  private static TransactionPosted payment(AccountId a, AccountId b, List<EventEntry> entries) {
    return new TransactionPosted(
        UUID.randomUUID(),
        new TransactionId(UUID.randomUUID()),
        "PAYMENT",
        BOOKING_TIME,
        UUID.randomUUID(),
        null,
        entries.isEmpty()
            ? List.of(
                new EventEntry(a, Side.DEBIT, 30_00, 2, 70_00),
                new EventEntry(b, Side.CREDIT, 30_00, 2, 70_00))
            : entries);
  }

  /** A CORRECTION Transaction that reverses the one given, with its Entries' sides swapped. */
  private static TransactionPosted correctionReversing(TransactionPosted original) {
    return new TransactionPosted(
        UUID.randomUUID(),
        new TransactionId(UUID.randomUUID()),
        "CORRECTION",
        BOOKING_TIME.plusSeconds(60),
        null,
        original.transaction(),
        original.entries().stream()
            .map(entry ->
                new EventEntry(
                    entry.account(),
                    entry.side() == Side.DEBIT ? Side.CREDIT : Side.DEBIT,
                    entry.amountPence(),
                    entry.entrySeq() + 1,
                    entry.balanceAfterPence() - entry.amountPence()))
            .toList());
  }

  private static List<Error> schemaErrors(String event) throws IOException {
    Schema schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(Files.readString(SCHEMA));
    return schema.validate(JSON.readTree(event));
  }
}
