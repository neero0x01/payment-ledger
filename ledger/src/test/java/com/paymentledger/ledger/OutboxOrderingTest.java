package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.payments.EndToEndId;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Events for the same key reach Kafka in the order they were written, even when more than one
 * outbox publisher runs, as with two ledger instances. The test plays a second publisher: while
 * Kafka is down it locks every unpublished outbox row the way a publisher's batch does, and holds
 * them after Kafka is back. Kafka is stopped, so the context has its own Kafka and Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({
  RestartableKafkaConfiguration.class,
  LedgerFixture.class,
  Sessions.class,
  FakePaymentScheme.class,
  SchemeCallbacks.class,
  PaymentStatusChangedEvents.class
})
class OutboxOrderingTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired FakePaymentScheme scheme;
  @Autowired SchemeCallbacks callbacks;
  @Autowired PaymentStatusChangedEvents statusEvents;
  @Autowired KafkaContainer kafka;
  @Autowired PostgreSQLContainer postgres;

  @Test
  void status_events_of_a_payment_are_published_in_order_while_another_publisher_holds_the_first()
      throws Exception {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    RestTestClient customer = sessions.customerOwning(a);
    String payeeAccountNumber = FasterPaymentTest.randomAccountNumber();

    RestartableKafkaConfiguration.stop(kafka);
    PaymentId payment = makeFasterPayment(customer, a, payeeAccountNumber);
    EndToEndId endToEndId = scheme.awaitSubmissionTo(payeeAccountNumber).submission().endToEndId();
    try (Connection otherPublisher =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      otherPublisher.setAutoCommit(false);
      try (Statement batch = otherPublisher.createStatement()) {
        // The PENDING event, as another publisher's batch locks it while it waits for Kafka.
        batch.execute("select id from outbox where published_at is null order by id for update");
      }

      callbacks.accept(endToEndId).expectStatus().isOk();
      RestartableKafkaConfiguration.start(kafka);
      await()
          .atMost(Duration.ofSeconds(60))
          .ignoreExceptions()
          .until(() -> TopicReader.readAll(kafka, PaymentStatusChangedEvents.TOPIC) != null);
      // Time enough for this ledger's publisher to send whatever it is free to send.
      Thread.sleep(5_000);
      otherPublisher.commit();
    }

    List<String> statuses =
        await()
            .atMost(Duration.ofSeconds(60))
            .until(() -> statusesPublishedFor(payment), list -> list.size() >= 2);
    assertThat(statuses).containsExactly("PENDING", "COMPLETED");
  }

  private List<String> statusesPublishedFor(PaymentId payment) {
    return statusEvents.publishedFor(payment).stream()
        .map(event -> JSON.readTree(event.value()).get("payload").get("status").asString())
        .toList();
  }

  private static PaymentId makeFasterPayment(
      RestTestClient customer, AccountId from, String payeeAccountNumber) {
    String body =
        customer
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(FasterPaymentTest.fasterPayment(from, payeeAccountNumber, 40_00))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    return new PaymentId(UUID.fromString(JSON.readTree(body).get("id").asString()));
  }
}
