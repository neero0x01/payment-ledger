package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.PaymentScheme;
import com.paymentledger.ledger.payments.SchemeSubmission;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends Pending Faster Payments to the scheme after they commit: the outbox pattern applied to
 * commands (ADR 0008). Each poll claims a batch in its own short database transaction, then calls
 * the PaymentScheme port outside any transaction. A Scheme Submission that fails gives its claim
 * back, so a poll after the retry delay sends it again.
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

  SchemeDispatcher(
      PaymentRepository payments, PaymentScheme scheme, TransactionTemplate transaction) {
    this.payments = payments;
    this.scheme = scheme;
    this.transaction = transaction;
  }

  @Scheduled(fixedDelayString = "${ledger.scheme.dispatch-interval-ms:200}")
  void dispatch() {
    List<SchemeSubmission> claimed =
        Objects.requireNonNull(
            transaction.execute(
                status -> payments.claimUnsubmittedFasterPayments(BATCH_SIZE, RETRY_DELAY)));
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
