package com.paymentledger.ledger.views.internal;

import com.paymentledger.ledger.outbox.Outbox;
import com.paymentledger.ledger.outbox.OutboxLag;
import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.payments.StuckPayment;
import com.paymentledger.ledger.payments.StuckPayments;
import com.paymentledger.ledger.posting.BalancedCheckResult;
import com.paymentledger.ledger.posting.BalancedChecks;
import com.paymentledger.ledger.views.internal.JdbcReadModel.PaymentFigures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Operator dashboard: live system health. Payments per second, outcomes and Rejection Reasons
 * come from the Read Model's per-minute figures: the rate over the last full minute, the outcomes
 * and Rejection Reasons over the last hour up to now. The outbox lag, the Projection Freshness, the
 * balanced check and the Stuck Payments come from the public APIs of the modules that own them (ADR
 * 0012). A figure whose database can't be read is null.
 */
@RestController
class DashboardController {

  private static final Duration OUTCOME_WINDOW = Duration.ofHours(1);
  private static final double SECONDS_PER_MINUTE = 60;
  private static final int STUCK_PAYMENTS_LISTED = 100;

  private final JdbcReadModel readModel;
  private final ProjectionFreshness freshness;
  private final Clock clock;
  private final Outbox outbox;
  private final BalancedChecks balancedChecks;
  private final StuckPayments stuckPayments;

  DashboardController(
      JdbcReadModel readModel,
      ProjectionFreshness freshness,
      Clock clock,
      Outbox outbox,
      BalancedChecks balancedChecks,
      StuckPayments stuckPayments) {
    this.readModel = readModel;
    this.freshness = freshness;
    this.clock = clock;
    this.outbox = outbox;
    this.balancedChecks = balancedChecks;
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
        outboxLag(),
        readable(() -> seconds(freshness.trailing())),
        balancedCheck(),
        readable(stuckPayments::count),
        stuckPayments.oldest(STUCK_PAYMENTS_LISTED).stream()
            .map(StuckPaymentResponse::of)
            .toList());
  }

  private OutboxLagResponse outboxLag() {
    OutboxLag lag = readable(outbox::lag);
    return lag == null
        ? new OutboxLagResponse(null, null)
        : new OutboxLagResponse(lag.unpublished(), seconds(lag.oldestAge()));
  }

  /** The last balanced check's result, or nulls before its first run. */
  private BalancedCheck balancedCheck() {
    return balancedChecks
        .last()
        .map(DashboardController::balancedCheck)
        .orElse(new BalancedCheck(null, null, null));
  }

  private static BalancedCheck balancedCheck(BalancedCheckResult result) {
    return new BalancedCheck(result.balanced(), result.imbalance().pence(), result.startedAt());
  }

  /** The figure, or null when its database can't be read. */
  private static <T> @Nullable T readable(Supplier<T> figure) {
    try {
      return figure.get();
    } catch (DataAccessException unreadable) {
      return null;
    }
  }

  private static double seconds(Duration duration) {
    return duration.toNanos() / 1e9;
  }

  /**
   * The dashboard. rejectionReasons counts the Payments Rejected over the last hour by each {@link
   * RejectionReason}. A figure whose database can't be read is null.
   */
  record DashboardResponse(
      double paymentsPerSecond,
      Outcomes outcomes,
      Map<String, Long> rejectionReasons,
      OutboxLagResponse outboxLag,
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
  record OutboxLagResponse(@Nullable Long unpublished, @Nullable Double oldestAgeSeconds) {}

  /**
   * Whether the last balanced check found a Balanced Ledger, the drift it found, and when it
   * started; all null before the first run.
   */
  record BalancedCheck(
      @Nullable Boolean balanced, @Nullable Long imbalancePence, @Nullable Instant lastRunAt) {}

  /** How many Payments became Completed and Rejected over the last hour. */
  record Outcomes(long completed, long rejected) {}
}
