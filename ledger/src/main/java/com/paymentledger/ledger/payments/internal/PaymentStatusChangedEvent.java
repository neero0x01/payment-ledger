package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.outbox.OutboxEvent;
import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.payments.SchemeReasonCode;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The PaymentStatusChanged event on {@code payments.payment-status-changed.v1}, keyed by Payment
 * id: the Payment as it stands after a change of status. Its JSON Schema is {@code
 * docs/schemas/payment-status-changed.v1.json}; a breaking change means a new topic (ADR 0006).
 */
final class PaymentStatusChangedEvent {

  static final String TOPIC = "payments.payment-status-changed.v1";
  static final String TYPE = "PaymentStatusChanged";
  static final int SCHEMA_VERSION = 1;

  private PaymentStatusChangedEvent() {}

  /** The event for a Payment that took its current status at the moment given. */
  static OutboxEvent of(Payment payment, Instant changedAt) {
    Payload payload =
        new Payload(
            payment.id().value(),
            payment.type().name(),
            payment.status().name(),
            payment.rejection().map(RejectionReason::name).orElse(null),
            payment.schemeRejection().map(SchemeReasonCode::name).orElse(null),
            payment.amount().pence());
    return new OutboxEvent(
        TOPIC, payment.id().value().toString(), TYPE, SCHEMA_VERSION, changedAt, payload);
  }

  record Payload(
      UUID paymentId,
      String type,
      String status,
      @Nullable String rejectionReason,
      @Nullable String schemeReasonCode,
      long amountPence) {}
}
