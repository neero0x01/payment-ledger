package com.paymentledger.ledger.views.internal;

import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.payments.StuckPayment;
import com.paymentledger.ledger.payments.StuckPayments;
import com.paymentledger.ledger.views.internal.JdbcReadModel.PaymentFigures;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Operator dashboard: live system health. Payments per second, outcomes and Rejection Reasons
 * come from the Read Model's per-minute figures: the rate over the last full minute, the outcomes
 * and Rejection Reasons over the last hour up to now. The outbox lag, the balanced check and the
 * Stuck Payment count come from their gauges, with the oldest Stuck Payments listed.
 */
@RestController
class DashboardController {

  private static final Duration OUTCOME_WINDOW = Duration.ofHours(1);
  private static final double SECONDS_PER_MINUTE = 60;
  private static final int STUCK_PAYMENTS_LISTED = 100;

  private final JdbcReadModel readModel;
  private final Clock clock;
  private final MeterRegistry gauges;
  private final StuckPayments stuckPayments;

  DashboardController(
      JdbcReadModel readModel, Clock clock, MeterRegistry gauges, StuckPayments stuckPayments) {
    this.readModel = readModel;
    this.clock = clock;
    this.gauges = gauges;
    this.stuckPayments = stuckPayments;
  }

  @GetMapping(path = "/{version}/dashboard", version = "1")
  DashboardResponse dashboard() {
    Instant thisMinute = clock.instant().truncatedTo(ChronoUnit.MINUTES);
    PaymentFigures figures =
        readModel.paymentFigures(
            thisMinute.minus(OUTCOME_WINDOW), thisMinute.minus(Duration.ofMinutes(1)));
    Map<String, Long> rejectionReasons = new TreeMap<>();
    figures.rejections().forEach((reason, count) -> rejectionReasons.put(reason.name(), count));
    return new DashboardResponse(
        figures.madeInMinute() / SECONDS_PER_MINUTE,
        new Outcomes(figures.completed(), figures.rejected()),
        rejectionReasons,
        new OutboxLag(count("ledger.outbox.unpublished"), reading("ledger.outbox.oldest.age")),
        reading(ProjectionFreshness.GAUGE),
        balancedCheck(),
        count("ledger.payments.stuck"),
        stuckPayments.oldest(STUCK_PAYMENTS_LISTED).stream()
            .map(StuckPaymentResponse::of)
            .toList());
  }

  /** The last balanced check's result, or nulls before its first run. */
  private BalancedCheck balancedCheck() {
    double balanced = gauge("ledger.balanced");
    if (Double.isNaN(balanced)) {
      return new BalancedCheck(null, null, null);
    }
    double lastRun = gauge("ledger.balanced.last.run");
    return new BalancedCheck(
        balanced == 1,
        (long) gauge("ledger.imbalance.pence"),
        Instant.ofEpochSecond(0, Math.round(lastRun * 1e9)));
  }

  /** The gauge's reading now, or null when it has none, as when its database can't be read. */
  private @Nullable Double reading(String name) {
    double value = gauge(name);
    return Double.isNaN(value) ? null : value;
  }

  /** {@link #reading} of a gauge that counts. */
  private @Nullable Long count(String name) {
    Double value = reading(name);
    return value == null ? null : value.longValue();
  }

  /** The gauge's value now, or NaN if nothing has registered it. */
  private double gauge(String name) {
    Gauge gauge = gauges.find(name).gauge();
    return gauge == null ? Double.NaN : gauge.value();
  }

  /**
   * The dashboard. rejectionReasons counts the Payments Rejected over the last hour by each {@link
   * RejectionReason}. A gauge with no reading, as when its database can't be read, is null.
   */
  record DashboardResponse(
      double paymentsPerSecond,
      Outcomes outcomes,
      Map<String, Long> rejectionReasons,
      OutboxLag outboxLag,
      @Nullable Double projectionFreshnessSeconds,
      BalancedCheck balancedCheck,
      @Nullable Long stuckPaymentCount,
      List<StuckPaymentResponse> stuckPayments) {}

  /** A Stuck Payment: the payer's Account, the Payee, the amount and when it was made. */
  record StuckPaymentResponse(
      UUID paymentId,
      UUID debtorAccountId,
      String payeeName,
      String payeeSortCode,
      String payeeAccountNumber,
      long amountPence,
      Instant madeAt) {

    static StuckPaymentResponse of(StuckPayment payment) {
      return new StuckPaymentResponse(
          payment.id().value(),
          payment.debtorAccount().value(),
          payment.payee().name(),
          payment.payee().sortCode(),
          payment.payee().accountNumber(),
          payment.amount().pence(),
          payment.madeAt());
    }
  }

  /** How many events wait to be published to Kafka, and how long the oldest has waited. */
  record OutboxLag(@Nullable Long unpublished, @Nullable Double oldestAgeSeconds) {}

  /**
   * Whether the last balanced check found a Balanced Ledger, the drift it found, and when it
   * started; all null before the first run.
   */
  record BalancedCheck(
      @Nullable Boolean balanced, @Nullable Long imbalancePence, @Nullable Instant lastRunAt) {}

  /** How many Payments became Completed and Rejected over the last hour. */
  record Outcomes(long completed, long rejected) {}
}
