package com.paymentledger.ledger.outbox.internal;

import com.paymentledger.ledger.outbox.Outbox;
import com.paymentledger.ledger.outbox.OutboxEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class JdbcOutbox implements Outbox {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  JdbcOutbox(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(OutboxEvent event) {
    UUID eventId = UUID.randomUUID();
    Envelope envelope =
        new Envelope(
            eventId, event.type(), event.schemaVersion(), event.occurredAt(), event.payload());
    jdbc.sql(
            """
            insert into outbox (event_id, topic, msg_key, payload)
            values (:eventId, :topic, :key, cast(:payload as jsonb))
            """)
        .param("eventId", eventId)
        .param("topic", event.topic())
        .param("key", event.key())
        .param("payload", json.writeValueAsString(envelope))
        .update();
  }

  /** The shared event envelope (ADR 0006), sent as the Kafka message's value. */
  record Envelope(
      UUID eventId, String type, int schemaVersion, Instant occurredAt, Object payload) {}
}
