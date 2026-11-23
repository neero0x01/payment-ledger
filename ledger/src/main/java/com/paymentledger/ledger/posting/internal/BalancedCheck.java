package com.paymentledger.ledger.posting.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The balanced check (spec §13), which proves the ledger is a Balanced Ledger: Σdebits = Σcredits
 * across every Entry. Operators read the result of the last run in the gauges ledger_balanced (1 or
 * 0), ledger_imbalance_pence and ledger_balanced_last_run_seconds.
 */
@Component
class BalancedCheck implements MeterBinder {

  private final JdbcClient jdbc;
  private volatile Result last = Result.NOT_RUN;

  BalancedCheck(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("ledger.balanced", this, check -> check.last.balanced())
        .description(
            "1 when the last balanced check found the ledger balanced, 0 when it found drift")
        .register(registry);
    Gauge.builder("ledger.imbalance.pence", this, check -> check.last.imbalance())
        .description("The total drift, in pence, the last balanced check found")
        .register(registry);
    Gauge.builder("ledger.balanced.last.run", this, check -> check.last.epochSeconds())
        .description("When the last balanced check started")
        .baseUnit("seconds")
        .register(registry);
  }

  @Scheduled(fixedDelayString = "${ledger.balanced-check.interval:PT60S}")
  synchronized void run() {
    Instant startedAt =
        jdbc.sql("select clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    last = new Result(startedAt, Math.abs(debitsMinusCredits()));
  }

  /** Σdebits − Σcredits over every Entry; zero in a Balanced Ledger. */
  private long debitsMinusCredits() {
    return jdbc.sql(
            """
            select coalesce(sum(case when side = 'DEBIT' then amount_pence else -amount_pence end), 0)
            from entry
            """)
        .query(Long.class)
        .single();
  }

  /** What a run found; before the first run, NaN gauges with a last run at the epoch. */
  private record Result(Instant startedAt, long imbalancePence) {

    static final Result NOT_RUN = new Result(Instant.EPOCH, 0);

    double balanced() {
      return this == NOT_RUN ? Double.NaN : imbalancePence == 0 ? 1 : 0;
    }

    double imbalance() {
      return this == NOT_RUN ? Double.NaN : imbalancePence;
    }

    double epochSeconds() {
      return startedAt.getEpochSecond() + startedAt.getNano() / 1e9;
    }
  }
}
