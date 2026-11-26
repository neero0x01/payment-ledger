package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.outbox.Outbox;
import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.PaymentStatus;
import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.payments.SchemeReasonCode;
import com.paymentledger.ledger.shared.PaymentId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owns every change of a Payment's Payment Status. Only a Pending Payment may become Completed or
 * Rejected, because those are final: each change is guarded in SQL by {@code status = 'PENDING'},
 * and a refused change is a broken invariant, so it throws. Each change reads the Payment back and
 * appends its PaymentStatusChanged event to the outbox, in the caller's database transaction (ADRs
 * 0004, 0005, 0007). Once that transaction commits, the change is logged, and a final one is
 * counted in {@code ledger.payments} by Payment type and outcome (spec §13); the Payment's id is in
 * the log context until the transaction ends.
 */
@Component
class PaymentLifecycle {

  static final String PAYMENT_ID = "paymentId";

  private static final Logger log = LoggerFactory.getLogger(PaymentLifecycle.class);

  private final JdbcClient jdbc;
  private final PaymentRepository payments;
  private final Outbox outbox;
  private final MeterRegistry meters;

  PaymentLifecycle(
      JdbcClient jdbc, PaymentRepository payments, Outbox outbox, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.payments = payments;
    this.outbox = outbox;
    this.meters = meters;
  }

  /** Announces a Payment just inserted as Pending, created at the time given. */
  Payment announcePending(PaymentId id, Instant createdAt) {
    Payment pending = payments.find(id).orElseThrow();
    outbox.append(PaymentStatusChangedEvent.of(pending, createdAt));
    afterCommit(pending);
    return pending;
  }

  /** Moves the Pending Payment to Completed. */
  Payment complete(PaymentId id) {
    return changed(
        id,
        jdbc.sql(
                """
                update payment
                set status = 'COMPLETED', completed_at = clock_timestamp()
                where id = :id and status = 'PENDING'
                returning completed_at as changed_at
                """)
            .param("id", id.value()));
  }

  /** Moves the Pending Payment to Rejected for the reason given. */
  Payment reject(PaymentId id, RejectionReason reason) {
    return changed(
        id,
        jdbc.sql(
                """
                update payment
                set status = 'REJECTED', rejection_reason = :reason
                where id = :id and status = 'PENDING'
                returning clock_timestamp() as changed_at
                """)
            .param("id", id.value())
            .param("reason", reason.name()));
  }

  /** Moves the Pending Payment to Rejected by a Scheme Rejection with the reason code. */
  Payment schemeReject(PaymentId id, SchemeReasonCode reasonCode) {
    return changed(
        id,
        jdbc.sql(
                """
                update payment
                set status = 'REJECTED', rejection_reason = 'SCHEME_REJECTED',
                    scheme_reason_code = :reasonCode
                where id = :id and status = 'PENDING'
                returning clock_timestamp() as changed_at
                """)
            .param("id", id.value())
            .param("reasonCode", reasonCode.name()));
  }

  private Payment changed(PaymentId id, JdbcClient.StatementSpec update) {
    Instant changedAt =
        update
            .query((rs, rowNum) -> rs.getObject("changed_at", OffsetDateTime.class).toInstant())
            .optional()
            .orElseThrow(() ->
                new IllegalStateException(
                    "Payment " + id.value() + " is not Pending, so its Payment Status is final"));
    Payment payment = payments.find(id).orElseThrow();
    outbox.append(PaymentStatusChangedEvent.of(payment, changedAt));
    afterCommit(payment);
    return payment;
  }

  /**
   * Puts the Payment's id in the log context until the database transaction ends, and logs and
   * counts its new Payment Status once the transaction commits, so a rolled-back change is neither.
   */
  private void afterCommit(Payment payment) {
    MDC.put(PAYMENT_ID, payment.id().value().toString());
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            log.info(
                "{} {} is {}{}",
                payment.type(),
                payment.id().value(),
                payment.status(),
                payment.rejection().map(reason -> ": " + reason).orElse(""));
            if (payment.status() != PaymentStatus.PENDING) {
              meters
                  .counter(
                      "ledger.payments",
                      "type",
                      payment.type().name(),
                      "outcome",
                      payment.status().name(),
                      "reason",
                      payment.rejection().map(RejectionReason::name).orElse("NONE"))
                  .increment();
            }
          }

          @Override
          public void afterCompletion(int status) {
            MDC.remove(PAYMENT_ID);
          }
        });
  }
}
