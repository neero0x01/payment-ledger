package com.paymentledger.ledger.outbox.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The outbox lag gauges (spec §11): how many events wait to be published, and how long the oldest
 * has waited. Both read 0 when the publisher has caught up.
 */
@Component
class OutboxLag implements MeterBinder {

  private final JdbcClient jdbc;

  OutboxLag(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("ledger.outbox.unpublished", this, OutboxLag::unpublished)
        .description("Outbox events not yet published to Kafka")
        .register(registry);
    Gauge.builder("ledger.outbox.oldest.age", this, OutboxLag::oldestAgeSeconds)
        .description("How long the oldest unpublished outbox event has waited")
        .baseUnit("seconds")
        .register(registry);
  }

  private double unpublished() {
    return jdbc.sql("select count(*) from outbox where published_at is null")
        .query(Long.class)
        .single();
  }

  private double oldestAgeSeconds() {
    return jdbc.sql(
            """
            select coalesce(extract(epoch from clock_timestamp() - min(created_at)), 0)
            from outbox
            where published_at is null
            """)
        .query(Double.class)
        .single();
  }
}
