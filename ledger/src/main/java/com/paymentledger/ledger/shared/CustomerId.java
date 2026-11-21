package com.paymentledger.ledger.shared;

import java.util.Objects;
import java.util.UUID;

/** Identifies a Customer. */
public record CustomerId(UUID value) {

  public CustomerId {
    Objects.requireNonNull(value, "value");
  }
}
