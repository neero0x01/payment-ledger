package com.paymentledger.ledger.outbox.internal;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The polling publisher (ADR 0005). Every 200 ms it takes a transaction-level advisory lock, so
 * only one publisher sends at a time however many ledger instances run, and the events for a key
 * reach Kafka in the order they were written. It then locks the oldest 500 unpublished outbox rows,
 * waiting for any row still locked rather than skipping it, sends them in order with the idempotent
 * producer and acks=all, and marks them published once Kafka has acknowledged every one. If any
 * send fails nothing is marked, and the whole batch is sent again on a later poll: delivery is at
 * least once, and consumers dedupe on eventId.
 */
@Component
class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  /** Longer than the producer's delivery.timeout.ms, so a send fails before this gives up. */
  private static final Duration SEND_TIMEOUT = Duration.ofSeconds(15);

  /** The advisory lock key only the outbox publisher takes. */
  private static final long PUBLISHER_LOCK = 0x6f7574626f78L;

  private final JdbcClient jdbc;
  private final KafkaTemplate<String, String> kafka;
  private final TransactionTemplate transaction;

  OutboxPublisher(
      JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate transaction) {
    this.jdbc = jdbc;
    this.kafka = kafka;
    this.transaction = transaction;
  }

  @Scheduled(fixedDelay = 200)
  void publishPending() {
    try {
      transaction.executeWithoutResult(status -> publishBatch());
    } catch (RuntimeException e) {
      log.warn("Outbox rows not published, retrying on the next poll: {}", e.toString());
    }
  }

  private void publishBatch() {
    if (!isTheOnlyPublisher()) {
      return;
    }
    List<PendingEvent> pending = lockUnpublished();
    if (pending.isEmpty()) {
      return;
    }
    CompletableFuture<?>[] sends =
        pending.stream()
            .map(event -> kafka.send(event.topic(), event.key(), event.payload()))
            .toArray(CompletableFuture<?>[]::new);
    awaitAcknowledgement(CompletableFuture.allOf(sends));
    markPublished(pending.stream().map(PendingEvent::id).toList());
  }

  /**
   * Takes the publisher's advisory lock until the database transaction ends, or answers false when
   * another publisher holds it.
   */
  private boolean isTheOnlyPublisher() {
    return jdbc.sql("select pg_try_advisory_xact_lock(:key)")
        .param("key", PUBLISHER_LOCK)
        .query(Boolean.class)
        .single();
  }

  private List<PendingEvent> lockUnpublished() {
    return jdbc.sql(
            """
            select id, topic, msg_key, payload::text as payload
            from outbox
            where published_at is null
            order by id
            limit 500
            for update
            """)
        .query((rs, rowNum) ->
            new PendingEvent(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("msg_key"),
                rs.getString("payload")))
        .list();
  }

  private void markPublished(List<Long> ids) {
    jdbc.sql("update outbox set published_at = clock_timestamp() where id in (:ids)")
        .param("ids", ids)
        .update();
  }

  private static void awaitAcknowledgement(CompletableFuture<?> sends) {
    try {
      sends.get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while sending outbox rows", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Kafka did not acknowledge every outbox row", e);
    }
  }

  /** An unpublished outbox row, locked until the publishing database transaction ends. */
  private record PendingEvent(long id, String topic, String key, String payload) {}
}
