package com.paymentledger.ledger;

import static com.paymentledger.ledger.PaymentStatusChangedProducer.PaymentStatusChanged.fasterPaymentCompleted;
import static com.paymentledger.ledger.PaymentStatusChangedProducer.PaymentStatusChanged.fasterPaymentPending;
import static com.paymentledger.ledger.PaymentStatusChangedProducer.PaymentStatusChanged.fasterPaymentSchemeRejected;
import static com.paymentledger.ledger.PaymentStatusChangedProducer.PaymentStatusChanged.internalTransferCompleted;
import static com.paymentledger.ledger.PaymentStatusChangedProducer.PaymentStatusChanged.rejectedForInsufficientFunds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.PaymentStatusChangedProducer.PaymentStatusChanged;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Screen 4, the Operator dashboard [M6]: GET /v1/dashboard serves the per-minute Read Model figures
 * the projector builds from PaymentStatusChanged events, with the outbox lag, Projection Freshness,
 * balanced-check and Stuck Payment gauges.
 *
 * <p>The context has its own Postgres and Kafka, closed after the class, so the figures count only
 * this class's Payments. Its clock can be moved forward, past every real Payment's minute. The
 * balanced check runs every second instead of every minute.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ledger.balanced-check.interval=PT1S")
@AutoConfigureRestTestClient
@Import({
  DedicatedContainersConfiguration.class,
  ProjectorConfiguration.class,
  MutableClock.Configuration.class,
  LedgerFixture.class,
  Sessions.class,
  PaymentStatusChangedProducer.class
})
@DirtiesContext
class DashboardTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Duration PROJECTION = Duration.ofSeconds(30);

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired MutableClock clock;
  @Autowired PaymentStatusChangedProducer events;

  @Test
  void only_an_operator_sees_the_dashboard() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(0));

    sessions
        .customerOwning(a)
        .get()
        .uri("/v1/dashboard")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/forbidden");
    sessions.operator().get().uri("/v1/dashboard").exchange().expectStatus().isOk();
  }

  @Test
  void dashboard_shows_payments_per_second_outcomes_and_rejection_reasons_of_the_last_hour() {
    RestTestClient operator = sessions.operator();
    // Past every real Payment's minute, so only these events count.
    clock.advance(Duration.ofDays(30));
    Instant lastMinute =
        clock.instant().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(1));
    Instant at = lastMinute.plusSeconds(10);
    Instant earlier = lastMinute.minus(Duration.ofMinutes(20));
    Instant beforeTheHour = lastMinute.minus(Duration.ofMinutes(90));
    PaymentStatusChanged deliveredTwice = internalTransferCompleted(10_00, at);

    events.send(
        // Six Payments made in the last full minute: 6 / 60 s.
        deliveredTwice,
        deliveredTwice,
        internalTransferCompleted(20_00, at),
        internalTransferCompleted(30_00, at),
        rejectedForInsufficientFunds("INTERNAL_TRANSFER", 40_00, at),
        fasterPaymentPending(50_00, at),
        fasterPaymentPending(60_00, at),
        // Outcomes of Faster Payments made earlier in the hour.
        fasterPaymentCompleted(70_00, at),
        fasterPaymentSchemeRejected(80_00, earlier),
        rejectedForInsufficientFunds("FASTER_PAYMENT", 90_00, earlier),
        // Before the last hour: not counted.
        internalTransferCompleted(1_00, beforeTheHour));

    JsonNode dashboard =
        await()
            .atMost(PROJECTION)
            .until(
                () -> dashboard(operator), d -> d.path("outcomes").path("rejected").asLong() == 3);
    assertThat(dashboard.get("paymentsPerSecond").asDouble()).isEqualTo(0.1);
    assertThat(dashboard.get("outcomes").get("completed").asLong()).isEqualTo(4);
    assertThat(dashboard.get("outcomes").get("rejected").asLong()).isEqualTo(3);
    assertThat(dashboard.get("rejectionReasons").get("INSUFFICIENT_FUNDS").asLong()).isEqualTo(2);
    assertThat(dashboard.get("rejectionReasons").get("SCHEME_REJECTED").asLong()).isEqualTo(1);
  }

  @Test
  void dashboard_shows_the_outbox_lag_and_the_balanced_status_with_the_time_of_the_last_check() {
    RestTestClient operator = sessions.operator();
    ledger.customerAccountWithBalance(Money.ofPence(100_00));
    Instant asked = Instant.now();

    JsonNode dashboard =
        await()
            .atMost(PROJECTION)
            .until(
                () -> dashboard(operator),
                d ->
                    d.path("outboxLag").path("unpublished").asLong(-1) == 0
                        && d.path("balancedCheck").path("lastRunAt").isString()
                        && Instant.parse(d.get("balancedCheck").get("lastRunAt").asString())
                            .isAfter(asked));
    assertThat(dashboard.get("outboxLag").get("oldestAgeSeconds").asDouble()).isZero();
    assertThat(dashboard.get("balancedCheck").get("balanced").asBoolean()).isTrue();
    assertThat(dashboard.get("balancedCheck").get("imbalancePence").asLong()).isZero();
  }

  private static JsonNode dashboard(RestTestClient operator) {
    String body =
        operator
            .get()
            .uri("/v1/dashboard")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    return JSON.readTree(body);
  }
}
