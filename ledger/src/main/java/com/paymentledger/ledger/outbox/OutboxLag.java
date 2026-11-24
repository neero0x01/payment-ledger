package com.paymentledger.ledger.outbox;

import java.time.Duration;
import java.util.Objects;

/**
 * How far publishing trails the outbox: how many events wait to be published, and how long the
 * oldest has waited. Both are zero when the publisher has caught up.
 */
public record OutboxLag(long unpublished, Duration oldestAge) {

  public OutboxLag {
    Objects.requireNonNull(oldestAge, "oldestAge");
  }
}
