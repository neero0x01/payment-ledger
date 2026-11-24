package com.paymentledger.seed;

import com.paymentledger.ledger.shared.CustomerId;
import java.time.Instant;
import java.util.Objects;

/** A synthetic Customer, with a name from Datafaker. */
public record SeededCustomer(CustomerId id, String displayName, Instant createdAt) {

  public SeededCustomer {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
