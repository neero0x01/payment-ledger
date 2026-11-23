package com.paymentledger.ledger.payments;

import java.util.Objects;
import java.util.UUID;

/**
 * The identifier that travels with a Faster Payment through the scheme, so both sides can recognise
 * a repeated Scheme Submission. At most 35 characters, as in ISO 20022.
 */
public record EndToEndId(String value) {

  public EndToEndId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank() || value.length() > 35) {
      throw new IllegalArgumentException("An End-to-End Id has 1 to 35 characters");
    }
  }

  /** A new End-to-End Id: 32 hex digits. */
  public static EndToEndId random() {
    return new EndToEndId(UUID.randomUUID().toString().replace("-", ""));
  }
}
