package com.paymentledger.ledger.views.internal;

import com.paymentledger.ledger.outbox.AppendedEvent;
import com.paymentledger.ledger.outbox.Outbox;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The Projection Freshness gauge (spec §13): how far the Read Model trails the ledger, as the age
 * of the oldest of the ledger's newest events that the projector has not applied yet, whether it
 * still waits in the outbox or on Kafka; 0 once it has applied them all. Only the newest {@value
 * #EVENTS_CHECKED} events are checked, so when the Read Model trails by more events than that, the
 * gauge reads the age of the oldest checked: it trails by at least that long.
 */
@Component
class ProjectionFreshness implements MeterBinder {

  static final String GAUGE = "ledger.projection.freshness";
  private static final int EVENTS_CHECKED = 1_000;

  private final Outbox outbox;
  private final JdbcReadModel readModel;

  ProjectionFreshness(Outbox outbox, JdbcReadModel readModel) {
    this.outbox = outbox;
    this.readModel = readModel;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder(GAUGE, this, freshness -> freshness.trailing().toMillis() / 1000.0)
        .description("How far the Read Model trails the ledger")
        .baseUnit("seconds")
        .register(registry);
  }

  private Duration trailing() {
    List<AppendedEvent> newest = outbox.newest(EVENTS_CHECKED);
    Set<UUID> applied = readModel.applied(newest.stream().map(AppendedEvent::eventId).toList());
    return newest.stream()
        .filter(event -> !applied.contains(event.eventId()))
        .map(AppendedEvent::age)
        .max(Duration::compareTo)
        .orElse(Duration.ZERO);
  }
}
