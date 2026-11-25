package com.paymentledger.ledger.posting.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps Entry partitions three months ahead of the current month (ADR 0010, spec §10). Months are
 * calendar months in UTC, like the partitions, and the current month comes from the database's
 * clock, which Booking Times come from too (ADR 0004). It runs when the ledger starts and then
 * daily, and creating a partition that exists does nothing, so a missed run is caught up by the
 * next. The missing next-month partition gauge (spec §11) reads 1 if next month has no partition,
 * which leaves weeks to fix the job before Entries booked then would be refused.
 */
@Component
class PartitionMaintenance implements MeterBinder {

  private final JdbcClient jdbc;

  PartitionMaintenance(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("ledger.entry.partition.next.month.missing", this, job -> job.nextMonthMissing())
        .description("1 when next month has no Entry partition, 0 when it has one")
        .register(registry);
  }

  @EventListener(ApplicationReadyEvent.class)
  void onStartup() {
    createPartitionsAhead();
  }

  /** Creates the current month's partition and the next three, unless they exist. */
  @Scheduled(cron = "${ledger.partition-maintenance.cron:0 0 3 * * *}", zone = "UTC")
  void createPartitionsAhead() {
    jdbc.sql(
            """
            select create_entry_partition(
                (date_trunc('month', clock_timestamp() at time zone 'UTC')
                    + make_interval(months => ahead))::date)
            from generate_series(0, 3) as ahead
            """)
        .query((rs, rowNum) -> rowNum)
        .list();
  }

  private double nextMonthMissing() {
    boolean exists =
        jdbc.sql(
                """
                select entry_partition_exists(
                    (date_trunc('month', clock_timestamp() at time zone 'UTC')
                        + interval '1 month')::date)
                """)
            .query(Boolean.class)
            .single();
    return exists ? 0 : 1;
  }
}
