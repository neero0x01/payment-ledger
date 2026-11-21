package com.paymentledger.ledger.shared;

import java.util.Objects;
import java.util.UUID;

/** Identifies a Payment. */
public record PaymentId(UUID value) {

  public PaymentId {
    Objects.requireNonNull(value, "value");
  }
}
