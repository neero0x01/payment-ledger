package com.paymentledger.ledger.outbox;

import java.time.Instant;
import java.util.Objects;

/**
 * An event to publish to a Kafka topic, keyed by the id of the thing it describes. It is sent in
 * the shared envelope (eventId, type, schemaVersion, occurredAt, payload) that ADR 0006 sets out,
 * with the payload written as JSON.
 */
public record OutboxEvent(
    String topic, String key, String type, int schemaVersion, Instant occurredAt, Object payload) {

  public OutboxEvent {
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(payload, "payload");
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("A schemaVersion starts at 1, was " + schemaVersion);
    }
  }
}
