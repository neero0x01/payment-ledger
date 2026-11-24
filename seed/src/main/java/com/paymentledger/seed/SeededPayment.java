package com.paymentledger.seed;

import com.paymentledger.ledger.payments.EndToEndId;
import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.PaymentType;
import com.paymentledger.ledger.shared.CustomerId;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A synthetic Payment in its final Payment Status, made by the Customer who owns its debtor
 * Account. A Completed Payment has the moment it completed; a Faster Payment has its End-to-End Id.
 */
public record SeededPayment(
    Payment payment,
    CustomerId principal,
    Instant createdAt,
    @Nullable Instant completedAt,
    @Nullable EndToEndId endToEndId) {

  public SeededPayment {
    Objects.requireNonNull(payment, "payment");
    Objects.requireNonNull(principal, "principal");
    Objects.requireNonNull(createdAt, "createdAt");
    if ((payment.type() == PaymentType.FASTER_PAYMENT) != (endToEndId != null)) {
      throw new IllegalArgumentException("A Faster Payment, and only one, has an End-to-End Id");
    }
  }
}
