package com.paymentledger.ledger.views.internal;

import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.views.internal.JdbcReadModel.PaymentFigures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Operator dashboard: live system health. Payments per second, outcomes and Rejection Reasons
 * come from the Read Model's per-minute figures: the rate over the last full minute, the outcomes
 * and Rejection Reasons over the last hour up to now.
 */
@RestController
class DashboardController {

  private static final Duration OUTCOME_WINDOW = Duration.ofHours(1);
  private static final double SECONDS_PER_MINUTE = 60;

  private final JdbcReadModel readModel;
  private final Clock clock;

  DashboardController(JdbcReadModel readModel, Clock clock) {
    this.readModel = readModel;
    this.clock = clock;
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
        rejectionReasons);
  }

  /**
   * The dashboard. rejectionReasons counts the Payments Rejected over the last hour by each {@link
   * RejectionReason}.
   */
  record DashboardResponse(
      double paymentsPerSecond, Outcomes outcomes, Map<String, Long> rejectionReasons) {}

  /** How many Payments became Completed and Rejected over the last hour. */
  record Outcomes(long completed, long rejected) {}
}
