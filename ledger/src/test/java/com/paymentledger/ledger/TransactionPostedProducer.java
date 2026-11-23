package com.paymentledger.ledger;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Side;
import com.paymentledger.ledger.shared.TransactionId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Puts TransactionPosted events on {@code ledger.transaction-posted.v1} the way the ledger's outbox
 * publisher does, so tests can deliver them twice or out of order.
 */
@Component
public class TransactionPostedProducer {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final KafkaContainer kafka;

  TransactionPostedProducer(KafkaContainer kafka) {
    this.kafka = kafka;
  }

  /** One Entry of an event's Transaction. */
  public record EventEntry(
      AccountId account, Side side, long amountPence, long entrySeq, long balanceAfterPence) {}

  /** A TransactionPosted event: the envelope of ADR 0006 around its payload. */
  public record TransactionPosted(
      UUID eventId,
      TransactionId transaction,
      String kind,
      Instant bookingTime,
      @Nullable UUID paymentId,
      @Nullable TransactionId reverses,
      List<EventEntry> entries) {

    /** A FUNDING Transaction with a new event id. */
    public static TransactionPosted funding(Instant bookingTime, EventEntry... entries) {
      return new TransactionPosted(
          UUID.randomUUID(),
          new TransactionId(UUID.randomUUID()),
          "FUNDING",
          bookingTime,
          null,
          null,
          List.of(entries));
    }

    /** The event as the ledger publishes it. */
    public String json() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("transactionId", transaction.value().toString());
      payload.put("kind", kind);
      payload.put("bookingTime", bookingTime.toString());
      payload.put("paymentId", paymentId == null ? null : paymentId.toString());
      payload.put("reversesTransactionId", reverses == null ? null : reverses.value().toString());
      payload.put(
          "entries",
          entries.stream()
              .map(entry ->
                  Map.of(
                      "accountId", entry.account().value().toString(),
                      "side", entry.side().name(),
                      "amountPence", entry.amountPence(),
                      "entrySeq", entry.entrySeq(),
                      "balanceAfterPence", entry.balanceAfterPence()))
              .toList());
      Map<String, Object> envelope = new LinkedHashMap<>();
      envelope.put("eventId", eventId.toString());
      envelope.put("type", "TransactionPosted");
      envelope.put("schemaVersion", 1);
      envelope.put("occurredAt", bookingTime.toString());
      envelope.put("payload", payload);
      return JSON.writeValueAsString(envelope);
    }
  }

  /** Sends the events in order, each keyed by its Transaction id, and waits for every ack. */
  public void send(TransactionPosted... events) {
    send(null, events);
  }

  /**
   * Sends the events in order to one partition, so a consumer also reads them in this order, and
   * waits for every ack.
   */
  public void sendToOnePartition(TransactionPosted... events) {
    send(0, events);
  }

  private void send(@Nullable Integer partition, TransactionPosted... events) {
    try (KafkaProducer<String, String> producer =
        new KafkaProducer<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()),
            new StringSerializer(),
            new StringSerializer())) {
      for (TransactionPosted event : events) {
        producer
            .send(
                new ProducerRecord<>(
                    TransactionPostedEvents.TOPIC,
                    partition,
                    event.transaction().value().toString(),
                    event.json()))
            .get();
      }
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
