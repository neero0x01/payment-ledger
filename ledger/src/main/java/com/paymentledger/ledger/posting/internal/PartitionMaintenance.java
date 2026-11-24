package com.paymentledger.ledger.posting.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps Entry partitions three months ahead of the current month (ADR 0010, spec §10). Months are
 * calendar months in UTC, like the partitions. It runs when the ledger starts and then daily, and
 * creating a partition that exists does nothing, so a missed run is caught up by the next. The
 * missing next-month partition gauge (spec §11) reads 1 if next month still has no partition, which
 * leaves weeks to fix the job before Entries booked then would be refused.
 */
@Component
class PartitionMaintenance implements MeterBinder {

  static final int MONTHS_AHEAD = 3;

  private final JdbcClient jdbc;
  private final Clock clock;

  PartitionMaintenance(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
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
    YearMonth current = YearMonth.now(clock);
    for (int ahead = 0; ahead <= MONTHS_AHEAD; ahead++) {
      jdbc.sql("select create_entry_partition(:month)")
          .param("month", current.plusMonths(ahead).atDay(1))
          .query((rs, rowNum) -> rowNum)
          .single();
    }
  }

  private double nextMonthMissing() {
    LocalDate nextMonth = YearMonth.now(clock).plusMonths(1).atDay(1);
    boolean exists =
        jdbc.sql("select entry_partition_exists(:month)")
            .param("month", nextMonth)
            .query(Boolean.class)
            .single();
    return exists ? 0 : 1;
  }
}
