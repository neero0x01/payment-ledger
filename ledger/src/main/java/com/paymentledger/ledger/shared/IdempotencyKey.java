package com.paymentledger.ledger.shared;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The value a client attaches to a Payment or Correction request so that sending it again never
 * creates a second one. Unique per principal.
 */
public record IdempotencyKey(String value) {

  public IdempotencyKey {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("An Idempotency-Key is never blank");
    }
  }

  /** The key a request sent in its Idempotency-Key header, refusing a request without one. */
  public static IdempotencyKey fromHeader(@Nullable String header) {
    if (header == null || header.isBlank()) {
      throw Problem.idempotencyKeyMissing("The request needs an Idempotency-Key header");
    }
    return new IdempotencyKey(header);
  }
}
