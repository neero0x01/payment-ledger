package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scenario 10, outbox half [M4]: every Payment status change is published to {@code
 * payments.payment-status-changed.v1}, keyed by Payment id, in the shared envelope its JSON Schema
 * describes. The producer side of the contract test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({
  TestcontainersConfiguration.class,
  LedgerFixture.class,
  Sessions.class,
  PaymentStatusChangedEvents.class,
  TransactionPostedEvents.class
})
class PaymentStatusChangedContractTest {

  private static final Path SCHEMA = Path.of("../docs/schemas/payment-status-changed.v1.json");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired PaymentStatusChangedEvents statusEvents;
  @Autowired TransactionPostedEvents transactionEvents;

  AccountId a;
  AccountId b;
  RestTestClient customer;

  @BeforeEach
  void givenAHas100PoundsAndBHasNothing() {
    a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    b = ledger.customerAccountWithBalance(Money.ofPence(0));
    customer = sessions.customerOwning(a);
  }

  @Test
  void completed_payment_is_published_keyed_by_its_id_in_a_schema_valid_envelope()
      throws IOException {
    JsonNode payment = transfer(30_00);
    PaymentId id = idOf(payment);

    List<ConsumerRecord<String, String>> published = statusEvents.publishedFor(id);

    assertThat(published).hasSize(1);
    String value = published.getFirst().value();
    assertThat(schemaErrors(value)).isEmpty();
    JsonNode event = JSON.readTree(value);
    assertThat(UUID.fromString(event.get("eventId").asString())).isNotNull();
    assertThat(event.get("type").asString()).isEqualTo("PaymentStatusChanged");
    assertThat(event.get("schemaVersion").asInt()).isEqualTo(1);
    JsonNode payload = event.get("payload");
    assertThat(payload.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(payload.get("rejectionReason").isNull()).isTrue();
    assertThat(payload.get("schemeReasonCode").isNull()).isTrue();
    assertPayloadDescribes(payload, payment);
  }

  /** The payload says what the Payment endpoint says about the Payment. */
  private static void assertPayloadDescribes(JsonNode payload, JsonNode payment) {
    assertThat(payload.get("paymentId")).isEqualTo(payment.get("id"));
    assertThat(payload.get("type")).isEqualTo(payment.get("type"));
    assertThat(payload.get("status")).isEqualTo(payment.get("status"));
    assertThat(payload.get("rejectionReason")).isEqualTo(payment.get("rejectionReason"));
    assertThat(payload.get("amountPence")).isEqualTo(payment.get("amountPence"));
  }

  private static List<Error> schemaErrors(String event) throws IOException {
    Schema schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(Files.readString(SCHEMA));
    return schema.validate(JSON.readTree(event));
  }

  private static PaymentId idOf(JsonNode payment) {
    return new PaymentId(UUID.fromString(payment.get("id").asString()));
  }

  /** Makes a Payment from A to B and returns the response body, whatever its outcome. */
  private JsonNode transfer(long amountPence) {
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
            .isCreated()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    return JSON.readTree(body);
  }
}
