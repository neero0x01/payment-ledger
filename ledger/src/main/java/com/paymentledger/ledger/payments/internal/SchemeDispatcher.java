package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.EndToEndId;
import com.paymentledger.ledger.payments.PaymentScheme;
import com.paymentledger.ledger.payments.SchemeSubmission;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends Pending Faster Payments to the scheme after they commit: the outbox pattern applied to
 * commands (ADR 0008). Each poll claims a batch in its own short database transaction, then calls
 * the PaymentScheme port outside any transaction. A Scheme Submission that fails gives its claim
 * back, so a poll after the retry delay sends it again. A Payment with no Scheme Outcome within the
 * resend delay (15 s) is sent again with the same End-to-End Id, which the scheme dedupes on. One
 * still Pending after the Scheme Outcome deadline (5 min) is flagged as a Stuck Payment for an
 * Operator, and is never reversed automatically, because the money may already have left. A Stuck
 * Payment is still sent with the same End-to-End Id, less often (every minute), so a scheme outage
 * longer than the deadline still ends with the Payment's Scheme Outcome.
 */
@Component
class SchemeDispatcher {

  private static final Logger log = LoggerFactory.getLogger(SchemeDispatcher.class);
  private static final int BATCH_SIZE = 50;

  /** How long after a failed Scheme Submission the Payment is sent again. */
  private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

  private final PaymentRepository payments;
  private final PaymentScheme scheme;
  private final TransactionTemplate transaction;
  private final Duration resendAfter;
  private final Duration stuckAfter;
  private final Duration stuckResendAfter;

  SchemeDispatcher(
      PaymentRepository payments,
      PaymentScheme scheme,
      TransactionTemplate transaction,
      @Value("${ledger.scheme.resend-after}") Duration resendAfter,
      @Value("${ledger.scheme.stuck-after}") Duration stuckAfter,
      @Value("${ledger.scheme.stuck-resend-after}") Duration stuckResendAfter) {
    this.payments = payments;
    this.scheme = scheme;
    this.transaction = transaction;
    this.resendAfter = resendAfter;
    this.stuckAfter = stuckAfter;
    this.stuckResendAfter = stuckResendAfter;
  }

  @Scheduled(fixedDelayString = "${ledger.scheme.dispatch-interval-ms:200}")
  void dispatch() {
    List<EndToEndId> stuck =
        Objects.requireNonNull(
            transaction.execute(status -> payments.flagStuckPayments(stuckAfter)));
    for (EndToEndId endToEndId : stuck) {
      log.warn(
          "Faster Payment with End-to-End Id {} has had no Scheme Outcome for {}: Stuck Payment",
          endToEndId.value(),
          stuckAfter);
    }
    List<SchemeSubmission> claimed =
        Objects.requireNonNull(
            transaction.execute(status ->
                payments.claimFasterPaymentsToSubmit(
                    BATCH_SIZE, RETRY_DELAY, resendAfter, stuckResendAfter)));
    for (SchemeSubmission submission : claimed) {
      try {
        scheme.submit(submission);
      } catch (RuntimeException e) {
        log.warn(
            "Scheme Submission of End-to-End Id {} failed, sending it again later: {}",
            submission.endToEndId().value(),
            e.toString());
        payments.releaseFailedSubmission(submission.endToEndId());
      }
    }
  }
}
