package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.posting.BalancedCheckResult;
import com.paymentledger.ledger.posting.BalancedChecks;
import com.paymentledger.ledger.posting.internal.ReadModelAgreement.ReadModelComparison;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The balanced check (spec §13), which proves the ledger is a Balanced Ledger. Every run checks
 * that Σdebits = Σcredits across every Entry ({@link LedgerTotals}), that the stored Balance of
 * each Account touched since the last run equals the Σ of its Entries ({@link StoredBalances}), and
 * that its Read Model Balance equals the write-side Balance ({@link ReadModelAgreement}). Operators
 * read the result of the last run in the gauges ledger_balanced (1 or 0), ledger_imbalance_pence
 * and ledger_balanced_last_run_seconds.
 *
 * <p>An Account found drifting, or trailed by the Read Model, is checked again on every later run
 * until it no longer is, so the gauges keep reporting drift that nothing touches again. A Read
 * Model that trails an Account beyond the grace period counts as drift too, by how far its Balance
 * is from the write side.
 */
@Component
class BalancedCheck implements MeterBinder, BalancedChecks {

  private final JdbcClient jdbc;
  private final LedgerTotals ledgerTotals;
  private final StoredBalances storedBalances;
  private final ReadModelAgreement readModelAgreement;
  private volatile Result last = Result.NOT_RUN;

  /**
   * The oldest transaction id still running when the last run started: every transaction that
   * changed a balance row since then, or was still to commit, has this id or a later one.
   */
  private @Nullable Long lastStartXmin;

  /** Accounts the last run found drifting, or trailed by the Read Model: checked on every run. */
  private Set<AccountId> unproven = Set.of();

  BalancedCheck(
      JdbcClient jdbc,
      LedgerTotals ledgerTotals,
      StoredBalances storedBalances,
      ReadModelAgreement readModelAgreement) {
    this.jdbc = jdbc;
    this.ledgerTotals = ledgerTotals;
    this.storedBalances = storedBalances;
    this.readModelAgreement = readModelAgreement;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("ledger.balanced", this, check -> check.last.balanced())
        .description(
            "1 when the last balanced check found the ledger balanced, 0 when it found drift"
                + " or a Read Model trailing an Account beyond the grace period")
        .register(registry);
    Gauge.builder("ledger.imbalance.pence", this, check -> check.last.imbalance())
        .description("The total drift, in pence, the last balanced check found")
        .register(registry);
    Gauge.builder("ledger.balanced.last.run", this, check -> check.last.epochSeconds())
        .description("When the last balanced check started")
        .baseUnit("seconds")
        .register(registry);
  }

  @Override
  public Optional<BalancedCheckResult> last() {
    Result result = last;
    if (result == Result.NOT_RUN) {
      return Optional.empty();
    }
    return Optional.of(
        new BalancedCheckResult(
            result.balanced() == 1, Money.ofPence(result.imbalancePence()), result.startedAt()));
  }

  /** Checks the Accounts touched since the last run; the first run checks every Account. */
  @Scheduled(fixedDelayString = "${ledger.balanced-check.interval:PT60S}")
  synchronized void run() {
    check(lastStartXmin == null);
  }

  /** The nightly full sweep, which checks every Account whether or not anything touched it. */
  @Scheduled(cron = "${ledger.balanced-check.full-sweep-cron:0 0 2 * * *}", zone = "Europe/London")
  synchronized void fullSweep() {
    check(true);
  }

  private void check(boolean everyAccount) {
    RunStart start =
        jdbc.sql(
                """
                select clock_timestamp() as started_at,
                       pg_snapshot_xmin(pg_current_snapshot())::text::bigint as xmin
                """)
            .query((rs, rowNum) ->
                new RunStart(
                    rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                    rs.getLong("xmin")))
            .single();
    Instant startedAt = start.startedAt();
    long imbalance = Math.abs(ledgerTotals.debitsMinusCredits());
    Set<AccountId> accounts;
    Map<AccountId, Money> storedDrifts;
    if (everyAccount || lastStartXmin == null) {
      accounts = Set.copyOf(storedBalances.allAccounts());
      storedDrifts = storedBalances.allDrifts();
    } else {
      accounts = new HashSet<>(unproven);
      accounts.addAll(storedBalances.touchedSince(lastStartXmin));
      storedDrifts = storedBalances.driftsOf(accounts);
    }
    ReadModelComparison readModel = readModelAgreement.compare(accounts, startedAt);
    for (Money drift : storedDrifts.values()) {
      imbalance += Math.abs(drift.pence());
    }
    for (Money drift : readModel.drifts().values()) {
      imbalance += Math.abs(drift.pence());
    }
    Set<AccountId> stillUnproven = new HashSet<>(storedDrifts.keySet());
    stillUnproven.addAll(readModel.drifts().keySet());
    stillUnproven.addAll(readModel.trailing());
    unproven = stillUnproven;
    lastStartXmin = start.xmin();
    last = new Result(startedAt, imbalance, readModel.trailingBeyondGrace().size());
  }

  /** When a run started, and the oldest transaction id still running then. */
  private record RunStart(Instant startedAt, long xmin) {}

  /** What a run found; before the first run, NaN gauges with a last run at the epoch. */
  private record Result(Instant startedAt, long imbalancePence, int accountsTrailingBeyondGrace) {

    static final Result NOT_RUN = new Result(Instant.EPOCH, 0, 0);

    double balanced() {
      if (this == NOT_RUN) {
        return Double.NaN;
      }
      return imbalancePence == 0 && accountsTrailingBeyondGrace == 0 ? 1 : 0;
    }

    double imbalance() {
      return this == NOT_RUN ? Double.NaN : imbalancePence;
    }

    double epochSeconds() {
      return startedAt.getEpochSecond() + startedAt.getNano() / 1e9;
    }
  }
}
