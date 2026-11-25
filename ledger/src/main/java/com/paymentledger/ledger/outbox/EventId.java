package com.paymentledger.ledger.outbox;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies one event in the shared envelope (ADR 0006). Consumers dedupe on it, since delivery is
 * at least once.
 */
public record EventId(UUID value) {

  public EventId {
    Objects.requireNonNull(value, "value");
  }

  /** A new random event id. */
  public static EventId random() {
    return new EventId(UUID.randomUUID());
  }
}
