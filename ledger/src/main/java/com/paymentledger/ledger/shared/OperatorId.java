package com.paymentledger.ledger.shared;

import java.util.Objects;
import java.util.UUID;

/** Identifies an Operator: the id of the login they log in with. */
public record OperatorId(UUID value) {

  public OperatorId {
    Objects.requireNonNull(value, "value");
  }
}
