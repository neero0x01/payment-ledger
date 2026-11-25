package com.paymentledger.ledger.outbox.internal;

import com.paymentledger.ledger.outbox.Outbox;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * The outbox lag gauges (spec §11): how many events wait to be published, and how long the oldest
 * has waited. Both read 0 when the publisher has caught up.
 */
@Component
class OutboxLagGauges implements MeterBinder {

  private final Outbox outbox;

  OutboxLagGauges(Outbox outbox) {
    this.outbox = outbox;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("ledger.outbox.unpublished", outbox, o -> o.lag().unpublished())
        .description("Outbox events not yet published to Kafka")
        .register(registry);
    Gauge.builder("ledger.outbox.oldest.age", outbox, o -> o.lag().oldestAge().toMillis() / 1000.0)
        .description("How long the oldest unpublished outbox event has waited")
        .baseUnit("seconds")
        .register(registry);
  }
}
