package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.TransactionId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Observability (spec §13): a Payment's trace reaches Kafka through the outbox, its outcome is
 * counted in the metrics Grafana Alloy scrapes from inside the box, and its log line carries the
 * Payment, Transaction and trace ids that Compose's ECS JSON format writes out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@AutoConfigureMetrics
@AutoConfigureTracing
@Import({
  TestcontainersConfiguration.class,
  LedgerFixture.class,
  Sessions.class,
  TransactionPostedEvents.class
})
class ObservabilityTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The trace a client is in when it makes the Payment. */
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

  private static final String PAYMENT_LIFECYCLE =
      "com.paymentledger.ledger.payments.internal.PaymentLifecycle";

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired TransactionPostedEvents events;

  AccountId a;
  AccountId b;
  RestTestClient customer;
  RestTestClient anonymous;

  @BeforeEach
  void givenAHas100PoundsAndBHasNothing() {
    a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    b = ledger.customerAccountWithBalance(Money.ofPence(0));
    customer = sessions.customerOwning(a);
    anonymous = sessions.anonymous();
  }

  @Test
  void payment_made_in_a_trace_is_published_to_kafka_in_the_same_trace() {
    JsonNode payment = transfer(30_00);

    List<ConsumerRecord<String, String>> published =
        events.publishedFor(firstTransactionOf(payment));

    assertThat(published).hasSize(1);
    Header traceparent = published.getFirst().headers().lastHeader("traceparent");
    assertThat(traceparent).isNotNull();
    assertThat(new String(traceparent.value(), StandardCharsets.UTF_8))
        .startsWith("00-" + TRACE_ID + "-")
        .doesNotContain("00f067aa0ba902b7");
  }

  @Test
  void payment_outcomes_are_counted_by_type_and_outcome_for_the_box_to_scrape() {
    transfer(30_00);
    transfer(500_00);

    String metrics = scrape(anonymous.get().uri("/actuator/prometheus"));

    assertThat(metrics)
        .contains(
            "ledger_payments_total{outcome=\"COMPLETED\",reason=\"NONE\",type=\"INTERNAL_TRANSFER\"}")
        .contains(
            "ledger_payments_total{outcome=\"REJECTED\",reason=\"INSUFFICIENT_FUNDS\","
                + "type=\"INTERNAL_TRANSFER\"}")
        .contains("ledger_balance_lock_wait_seconds_bucket{le=\"0.1\"}")
        .contains("ledger_outbox_unpublished")
        .contains("ledger_projection_freshness_seconds");
    assertThat(
            metrics.lines().filter(line -> line.startsWith("http_server_requests_seconds_bucket")))
        .as("POST latency has only the capped buckets")
        .allMatch(line ->
            line.matches(".*le=\"(0\\.01|0\\.025|0\\.05|0\\.1|0\\.2|0\\.5|1\\.0|2\\.0|\\+Inf)\".*"))
        .anyMatch(
            line -> line.contains("uri=\"/{version}/payments\"") && line.contains("le=\"0.2\""));
  }

  @Test
  void metrics_are_refused_to_a_visitor_from_outside_the_box() {
    anonymous
        .get()
        .uri("/actuator/prometheus")
        // As Caddy forwards a visitor's request.
        .header("X-Forwarded-For", "203.0.113.7")
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void payment_log_line_carries_its_payment_transaction_and_trace_ids() {
    ListAppender<ILoggingEvent> logged = new ListAppender<>();
    logged.start();
    Logger lifecycle = (Logger) LoggerFactory.getLogger(PAYMENT_LIFECYCLE);
    lifecycle.addAppender(logged);
    JsonNode payment;
    try {
      payment = transfer(30_00);
    } finally {
      lifecycle.detachAppender(logged);
    }
    String paymentId = payment.get("id").asString();

    ILoggingEvent logLine =
        logged.list.stream()
            .filter(event -> event.getFormattedMessage().contains(paymentId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No log line names Payment " + paymentId));

    assertThat(logLine.getFormattedMessage()).contains("COMPLETED");
    assertThat(logLine.getMDCPropertyMap())
        .containsEntry("paymentId", paymentId)
        .containsEntry("transactionId", firstTransactionOf(payment).value().toString())
        .containsEntry("traceId", TRACE_ID);
  }

  /** Customer A pays B the amount, in the client's trace; answers the Payment. */
  private JsonNode transfer(long amountPence) {
    String body =
        customer
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("traceparent", TRACEPARENT)
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
    return JSON.readTree(body);
  }

  private static TransactionId firstTransactionOf(JsonNode payment) {
    return new TransactionId(UUID.fromString(payment.get("transactionIds").get(0).asString()));
  }

  private static String scrape(RestTestClient.RequestHeadersSpec<?> request) {
    return request
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }
}
