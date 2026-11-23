package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The balanced check (spec §13), which proves the ledger is a Balanced Ledger. Every run checks
 * that Σdebits = Σcredits across every Entry, and that the stored Balance of each Account touched
 * since the last run equals the Σ of its Entries. Operators read the result of the last run in the
 * gauges ledger_balanced (1 or 0), ledger_imbalance_pence and ledger_balanced_last_run_seconds.
 *
 * <p>An Account found drifting is checked again on every later run until it no longer drifts, so
 * the gauges keep reporting drift that nothing touches again.
 */
@Component
class BalancedCheck implements MeterBinder {

  /**
   * How far back before the last run's start a touch still counts: a posting stamps its balance row
   * before it commits, so a commit landing just after a run started is caught by the next.
   */
  private static final Duration COMMIT_SLACK = Duration.ofSeconds(5);

  private final JdbcClient jdbc;
  private volatile Result last = Result.NOT_RUN;
  private @Nullable Instant lastStartedAt;
  private Set<AccountId> drifting = Set.of();

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

  /** Checks the Accounts touched since the last run; the first run checks every Account. */
  @Scheduled(fixedDelayString = "${ledger.balanced-check.interval:PT60S}")
  synchronized void run() {
    Instant startedAt =
        jdbc.sql("select clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    long imbalance = Math.abs(debitsMinusCredits());
    Map<AccountId, Money> drifts;
    if (lastStartedAt == null) {
      drifts = allStoredBalanceDrifts();
    } else {
      Set<AccountId> accounts = new HashSet<>(drifting);
      accounts.addAll(touchedSince(lastStartedAt.minus(COMMIT_SLACK)));
      drifts = storedBalanceDrifts(accounts);
    }
    for (Money drift : drifts.values()) {
      imbalance += Math.abs(drift.pence());
    }
    drifting = Set.copyOf(drifts.keySet());
    lastStartedAt = startedAt;
    last = new Result(startedAt, imbalance);
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

  /** The Accounts whose balance row a posting changed at or after the moment. */
  private List<AccountId> touchedSince(Instant moment) {
    return jdbc.sql("select account_id from account_balance where updated_at >= :since")
        .param("since", moment.atOffset(ZoneOffset.UTC))
        .query((rs, rowNum) -> new AccountId(rs.getObject("account_id", UUID.class)))
        .list();
  }

  /**
   * How far each of the Accounts' stored Balance is from the Σ of its Entries, on its Normal Side,
   * for the Accounts where they differ.
   */
  private Map<AccountId, Money> storedBalanceDrifts(Collection<AccountId> accounts) {
    if (accounts.isEmpty()) {
      return Map.of();
    }
    return toDrifts(
        jdbc.sql(
                """
                select account_id, drift_pence
                from (
                    select b.account_id,
                           b.balance_pence - coalesce(sum(
                               case when e.side = a.normal_side then e.amount_pence
                                    else -e.amount_pence end), 0) as drift_pence
                    from account_balance b
                    join account a on a.id = b.account_id
                    left join entry e on e.account_id = b.account_id
                    where b.account_id in (:accountIds)
                    group by b.account_id, b.balance_pence
                ) balances
                where drift_pence <> 0
                """)
            .param("accountIds", accounts.stream().map(AccountId::value).toList()));
  }

  /** {@link #storedBalanceDrifts} over every Account. */
  private Map<AccountId, Money> allStoredBalanceDrifts() {
    return toDrifts(
        jdbc.sql(
            """
            select account_id, drift_pence
            from (
                select b.account_id,
                       b.balance_pence - coalesce(sum(
                           case when e.side = a.normal_side then e.amount_pence
                                else -e.amount_pence end), 0) as drift_pence
                from account_balance b
                join account a on a.id = b.account_id
                left join entry e on e.account_id = b.account_id
                group by b.account_id, b.balance_pence
            ) balances
            where drift_pence <> 0
            """));
  }

  private static Map<AccountId, Money> toDrifts(JdbcClient.StatementSpec query) {
    return query
        .query((rs, rowNum) ->
            Map.entry(
                new AccountId(rs.getObject("account_id", UUID.class)),
                Money.ofPence(rs.getLong("drift_pence"))))
        .list()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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
