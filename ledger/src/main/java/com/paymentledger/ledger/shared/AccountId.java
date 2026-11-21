package com.paymentledger.ledger.shared;

import java.util.Objects;
import java.util.UUID;

/** Identifies an Account. */
public record AccountId(UUID value) {

  public AccountId {
    Objects.requireNonNull(value, "value");
  }
}
