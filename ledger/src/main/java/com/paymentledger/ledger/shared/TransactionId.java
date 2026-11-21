package com.paymentledger.ledger.shared;

import java.util.Objects;
import java.util.UUID;

/** Identifies a Transaction. */
public record TransactionId(UUID value) {

  public TransactionId {
    Objects.requireNonNull(value, "value");
  }
}
