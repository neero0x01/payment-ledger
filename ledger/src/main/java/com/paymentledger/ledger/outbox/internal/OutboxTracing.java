package com.paymentledger.ledger.outbox.internal;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Carries a trace across the outbox (spec §13). The outbox row stores the W3C traceparent of the
 * request that appended it; the publisher, which runs on its own thread long after that request,
 * starts a producer span under it for each send and puts that span's traceparent on the Kafka
 * record, where the projector picks the trace up.
 */
@Component
class OutboxTracing {

  static final String TRACEPARENT = "traceparent";

  private final Tracer tracer;
  private final Propagator propagator;

  OutboxTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
    this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
    this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
  }

  /** The traceparent of the trace in progress, or null outside one. */
  @Nullable String currentTraceparent() {
    TraceContext context = tracer.currentTraceContext().context();
    if (context == null) {
      return null;
    }
    Map<String, String> carrier = new HashMap<>();
    propagator.inject(context, carrier, Map::put);
    return carrier.get(TRACEPARENT);
  }

  /**
   * Starts the span that sends the record, as a child of the stored traceparent, and puts the
   * span's own traceparent on the record. Answers null, and leaves the record untraced, when the
   * row was appended outside a trace: seeded and scheduled events start no trace of their own.
   */
  @Nullable Span startSend(@Nullable String traceparent, ProducerRecord<String, String> record) {
    if (traceparent == null) {
      return null;
    }
    Span span =
        propagator
            .extract(Map.of(TRACEPARENT, traceparent), Map::get)
            .name(record.topic() + " send")
            .kind(Span.Kind.PRODUCER)
            .tag("messaging.system", "kafka")
            .tag("messaging.destination.name", record.topic())
            .start();
    propagator.inject(
        span.context(),
        record,
        (r, key, value) -> r.headers().add(key, value.getBytes(StandardCharsets.UTF_8)));
    return span;
  }
}
