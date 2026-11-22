package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.TransactionId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scenario 10, outbox half [M4]: every posted Transaction is published to {@code
 * ledger.transaction-posted.v1}, keyed by its id, in the shared envelope its JSON Schema describes.
 * The producer side of the contract test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({
  TestcontainersConfiguration.class,
  LedgerFixture.class,
  Sessions.class,
  TransactionPostedEvents.class
})
class TransactionPostedContractTest {

  private static final Path SCHEMA = Path.of("../docs/schemas/transaction-posted.v1.json");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired TransactionPostedEvents events;

  AccountId a;
  AccountId b;
  RestTestClient customer;
  RestTestClient operator;

  @BeforeEach
  void givenAHas100PoundsAndBHasNothing() {
    a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    b = ledger.customerAccountWithBalance(Money.ofPence(0));
    customer = sessions.customerOwning(a);
    operator = sessions.operator();
  }

  @Test
  void payment_transaction_is_published_keyed_by_its_id_in_a_schema_valid_envelope()
      throws IOException {
    TransactionId transaction = transferAndReturnTransaction(30_00);

    List<ConsumerRecord<String, String>> published = events.publishedFor(transaction);

    assertThat(published).hasSize(1);
    String value = published.getFirst().value();
    assertThat(schemaErrors(value)).isEmpty();
    JsonNode event = JSON.readTree(value);
    assertThat(UUID.fromString(event.get("eventId").asString())).isNotNull();
    assertThat(event.get("type").asString()).isEqualTo("TransactionPosted");
    assertThat(event.get("schemaVersion").asInt()).isEqualTo(1);
    assertPayloadDescribes(event, transaction);
  }

  @Test
  void reversing_correction_is_published_with_the_transaction_it_reverses() throws IOException {
    TransactionId payment = transferAndReturnTransaction(30_00);
    TransactionId correction = reverse(payment);

    List<ConsumerRecord<String, String>> published = events.publishedFor(correction);

    assertThat(published).hasSize(1);
    String value = published.getFirst().value();
    assertThat(schemaErrors(value)).isEmpty();
    JsonNode event = JSON.readTree(value);
    assertThat(event.get("payload").get("kind").asString()).isEqualTo("CORRECTION");
    assertThat(event.get("payload").get("reversesTransactionId").asString())
        .isEqualTo(payment.value().toString());
    assertPayloadDescribes(event, correction);
  }

  @Test
  void funding_transactions_are_published_too() {
    TransactionId funding = ledger.lastFundingOf(a);

    assertThat(events.publishedFor(funding)).hasSize(1);
  }

  /** The payload says what the Transaction endpoint says about the Transaction. */
  private void assertPayloadDescribes(JsonNode event, TransactionId id) {
    JsonNode transaction = transactionResponse(id);
    JsonNode payload = event.get("payload");
    assertThat(payload.get("transactionId").asString()).isEqualTo(id.value().toString());
    assertThat(payload.get("kind")).isEqualTo(transaction.get("kind"));
    assertThat(payload.get("paymentId")).isEqualTo(transaction.get("paymentId"));
    assertThat(payload.get("reversesTransactionId"))
        .isEqualTo(transaction.get("reversesTransactionId"));
    assertThat(Instant.parse(payload.get("bookingTime").asString()))
        .isEqualTo(Instant.parse(transaction.get("bookingTime").asString()));
    assertThat(Instant.parse(event.get("occurredAt").asString()))
        .isEqualTo(Instant.parse(transaction.get("bookingTime").asString()));
    assertThat(payload.get("entries"))
        .containsExactlyInAnyOrderElementsOf(transaction.get("entries"));
  }

  private static List<Error> schemaErrors(String event) throws IOException {
    Schema schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(Files.readString(SCHEMA));
    return schema.validate(JSON.readTree(event));
  }

  private JsonNode transactionResponse(TransactionId id) {
    String body =
        operator
            .get()
            .uri("/v1/transactions/{id}", id.value())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    return JSON.readTree(body);
  }

  private TransactionId transferAndReturnTransaction(long amountPence) {
    String body =
        customer
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": %d}
                """
                    .formatted(a.value(), b.value(), amountPence))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    return new TransactionId(
        UUID.fromString(JSON.readTree(body).get("transactionIds").get(0).asString()));
  }

  private TransactionId reverse(TransactionId transaction) {
    String body =
        operator
            .post()
            .uri("/v1/corrections")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"kind": "REVERSING", "transactionId": "%s", "reason": "DUPLICATE_POSTING",
                 "note": "Paid twice by mistake"}
                """
                    .formatted(transaction.value()))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    return new TransactionId(UUID.fromString(JSON.readTree(body).get("id").asString()));
  }
}
