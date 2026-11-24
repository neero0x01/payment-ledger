package com.paymentledger.ledger;

import java.time.Instant;
import java.util.LinkedHashMap;
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
 * Puts PaymentStatusChanged events on {@code payments.payment-status-changed.v1} the way the
 * ledger's outbox publisher does, so tests choose when each happened and can deliver one twice.
 */
@Component
public class PaymentStatusChangedProducer {

  private static final String TOPIC = "payments.payment-status-changed.v1";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final KafkaContainer kafka;

  PaymentStatusChangedProducer(KafkaContainer kafka) {
    this.kafka = kafka;
  }

  /** A PaymentStatusChanged event: a Payment that took the status at the moment given. */
  public record PaymentStatusChanged(
      UUID eventId,
      UUID paymentId,
      String type,
      String status,
      @Nullable String rejectionReason,
      @Nullable String schemeReasonCode,
      long amountPence,
      Instant occurredAt) {

    public static PaymentStatusChanged internalTransferCompleted(long pence, Instant at) {
      return of("INTERNAL_TRANSFER", "COMPLETED", null, null, pence, at);
    }

    public static PaymentStatusChanged rejectedForInsufficientFunds(
        String type, long pence, Instant at) {
      return of(type, "REJECTED", "INSUFFICIENT_FUNDS", null, pence, at);
    }

    public static PaymentStatusChanged fasterPaymentPending(long pence, Instant at) {
      return of("FASTER_PAYMENT", "PENDING", null, null, pence, at);
    }

    public static PaymentStatusChanged fasterPaymentCompleted(long pence, Instant at) {
      return of("FASTER_PAYMENT", "COMPLETED", null, null, pence, at);
    }

    public static PaymentStatusChanged fasterPaymentSchemeRejected(long pence, Instant at) {
      return of("FASTER_PAYMENT", "REJECTED", "SCHEME_REJECTED", "AC04", pence, at);
    }

    private static PaymentStatusChanged of(
        String type,
        String status,
        @Nullable String rejectionReason,
        @Nullable String schemeReasonCode,
        long pence,
        Instant at) {
      return new PaymentStatusChanged(
          UUID.randomUUID(),
          UUID.randomUUID(),
          type,
          status,
          rejectionReason,
          schemeReasonCode,
          pence,
          at);
    }

    /** The event as the ledger publishes it. */
    String json() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("paymentId", paymentId.toString());
      payload.put("type", type);
      payload.put("status", status);
      payload.put("rejectionReason", rejectionReason);
      payload.put("schemeReasonCode", schemeReasonCode);
      payload.put("amountPence", amountPence);
      Map<String, Object> envelope = new LinkedHashMap<>();
      envelope.put("eventId", eventId.toString());
      envelope.put("type", "PaymentStatusChanged");
      envelope.put("schemaVersion", 1);
      envelope.put("occurredAt", occurredAt.toString());
      envelope.put("payload", payload);
      return JSON.writeValueAsString(envelope);
    }
  }

  /** Sends the events in order, each keyed by its Payment id, and waits for every ack. */
  public void send(PaymentStatusChanged... events) {
    try (KafkaProducer<String, String> producer =
        new KafkaProducer<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()),
            new StringSerializer(),
            new StringSerializer())) {
      for (PaymentStatusChanged event : events) {
        producer
            .send(new ProducerRecord<>(TOPIC, event.paymentId().toString(), event.json()))
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
