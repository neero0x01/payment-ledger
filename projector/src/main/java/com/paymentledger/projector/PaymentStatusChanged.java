package com.paymentledger.projector;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A PaymentStatusChanged event from {@code payments.payment-status-changed.v1}: the envelope of ADR
 * 0006 around its payload, as {@code docs/schemas/payment-status-changed.v1.json} describes it.
 * Only the fields the projector reads.
 */
record PaymentStatusChanged(UUID eventId, Instant occurredAt, Payload payload) {

  record Payload(String type, String status, @Nullable String rejectionReason, long amountPence) {}
}
