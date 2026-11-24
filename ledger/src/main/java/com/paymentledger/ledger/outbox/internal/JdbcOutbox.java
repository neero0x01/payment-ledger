package com.paymentledger.ledger.outbox.internal;

import com.paymentledger.ledger.outbox.AppendedEvent;
import com.paymentledger.ledger.outbox.EventId;
import com.paymentledger.ledger.outbox.Outbox;
import com.paymentledger.ledger.outbox.OutboxEvent;
import com.paymentledger.ledger.outbox.OutboxLag;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    EventId eventId = EventId.random();
    Envelope envelope =
        new Envelope(
            eventId.value(),
            event.type(),
            event.schemaVersion(),
            event.occurredAt(),
            event.payload());
    jdbc.sql(
            """
            insert into outbox (event_id, topic, msg_key, payload)
            values (:eventId, :topic, :key, cast(:payload as jsonb))
            """)
        .param("eventId", eventId.value())
        .param("topic", event.topic())
        .param("key", event.key())
        .param("payload", json.writeValueAsString(envelope))
        .update();
  }

  @Override
  public List<AppendedEvent> newest(int limit) {
    return jdbc.sql(
            """
            select event_id,
                   greatest(extract(epoch from clock_timestamp() - created_at), 0) as age_seconds
            from outbox
            order by id desc
            limit :limit
            """)
        .param("limit", limit)
        .query((rs, rowNum) ->
            new AppendedEvent(
                new EventId(rs.getObject("event_id", UUID.class)),
                Duration.ofNanos(Math.round(rs.getDouble("age_seconds") * 1e9))))
        .list();
  }

  @Override
  public OutboxLag lag() {
    return jdbc.sql(
            """
            select count(*) as unpublished,
                   coalesce(extract(epoch from clock_timestamp() - min(created_at)), 0)
                       as oldest_age_seconds
            from outbox
            where published_at is null
            """)
        .query((rs, rowNum) ->
            new OutboxLag(
                rs.getLong("unpublished"),
                Duration.ofNanos(Math.round(rs.getDouble("oldest_age_seconds") * 1e9))))
        .single();
  }

  /** The shared event envelope (ADR 0006), sent as the Kafka message's value. */
  record Envelope(
      UUID eventId, String type, int schemaVersion, Instant occurredAt, Object payload) {}
}
