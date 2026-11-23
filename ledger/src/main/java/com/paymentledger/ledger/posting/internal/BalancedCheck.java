package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.posting.ReadModelBalance;
import com.paymentledger.ledger.posting.ReadModelBalances;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The balanced check (spec §13), which proves the ledger is a Balanced Ledger. Every run checks
 * that Σdebits = Σcredits across every Entry, and that the stored Balance of each Account touched
 * since the last run equals the Σ of its Entries. Operators read the result of the last run in the
 * gauges ledger_balanced (1 or 0), ledger_imbalance_pence and ledger_balanced_last_run_seconds.
 *
 * <p>An Account found drifting, or trailed by the Read Model, is checked again on every later run
 * until it no longer is, so the gauges keep reporting drift that nothing touches again. A Read
 * Model that trails an Account by more than the allowed Projection Freshness (its oldest
 * unprojected Entry was booked longer ago) counts as drift too, by how far its Balance is from the
 * write side.
 */
@Component
class BalancedCheck implements MeterBinder {

  private static final Logger log = LoggerFactory.getLogger(BalancedCheck.class);

  private final JdbcClient jdbc;
  private final ReadModelBalances readModelBalances;
  private final Duration allowedFreshness;
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
      ReadModelBalances readModelBalances,
      @Value("${ledger.balanced-check.projection-freshness:PT60S}") Duration allowedFreshness) {
    this.jdbc = jdbc;
    this.readModelBalances = readModelBalances;
    this.allowedFreshness = allowedFreshness;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("ledger.balanced", this, check -> check.last.balanced())
        .description(
            "1 when the last balanced check found the ledger balanced, 0 when it found drift"
                + " or a Read Model trailing by more than the allowed Projection Freshness")
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
    long imbalance = Math.abs(debitsMinusCredits());
    Set<AccountId> accounts;
    Map<AccountId, Money> storedDrifts;
    if (everyAccount || lastStartXmin == null) {
      accounts = Set.copyOf(allAccountsWithBalances());
      storedDrifts = allStoredBalanceDrifts();
    } else {
      accounts = new HashSet<>(unproven);
      accounts.addAll(touchedSince(lastStartXmin));
      storedDrifts = storedBalanceDrifts(accounts);
    }
    ReadModelComparison readModel = compareWithReadModel(accounts, startedAt);
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
    last = new Result(startedAt, imbalance, readModel.trailingTooLong().size());
  }

  /**
   * Compares each Account's Read Model Balance with the write-side Balance after the same
   * entry_seq, so a Read Model that only trails the ledger is no drift. An Account the Read Model
   * trails is returned to be checked again once it catches up; so is every Account when the Read
   * Model can't be read. An Account whose oldest unprojected Entry was booked longer than the
   * allowed Projection Freshness before the run started is drift, by how far the Read Model Balance
   * is from the write-side Balance.
   */
  private ReadModelComparison compareWithReadModel(Set<AccountId> accounts, Instant startedAt) {
    Map<AccountId, ReadModelBalance> projected;
    try {
      projected = readModelBalances.readModelBalancesOf(accounts);
    } catch (DataAccessException e) {
      log.warn("The balanced check could not read the Read Model: {}", e.getMessage());
      return new ReadModelComparison(Map.of(), accounts, Set.of());
    }
    Map<AccountId, WriteSideBalance> written = writeSideBalancesOf(accounts);
    Map<AccountId, Money> drifts = new HashMap<>();
    Set<AccountId> trailing = new HashSet<>();
    Map<AccountId, ReadModelBalance> atSeq = new HashMap<>();
    Map<AccountId, Long> firstUnprojected = new HashMap<>();
    for (AccountId account : accounts) {
      ReadModelBalance readModel = projected.get(account);
      WriteSideBalance writeSide = written.get(account);
      if (writeSide == null) {
        continue;
      }
      if (readModel == null) {
        trailing.add(account);
        firstUnprojected.put(account, 1L);
      } else if (readModel.lastEntrySeq() > writeSide.lastEntrySeq()) {
        // The Read Model holds an Entry the ledger never posted.
        drifts.put(account, readModel.balance().plus(writeSide.balance().negate()));
      } else {
        if (readModel.lastEntrySeq() < writeSide.lastEntrySeq()) {
          trailing.add(account);
          firstUnprojected.put(account, readModel.lastEntrySeq() + 1);
        }
        atSeq.put(account, readModel);
      }
    }
    Map<AccountId, Map<Long, Money>> balancesAfter = balancesAfter(atSeq);
    atSeq.forEach((account, readModel) -> {
      Money writeSide = balancesAfter.getOrDefault(account, Map.of()).get(readModel.lastEntrySeq());
      Money drift =
          writeSide == null ? readModel.balance() : readModel.balance().plus(writeSide.negate());
      if (drift.pence() != 0) {
        drifts.put(account, drift);
      }
    });
    Set<AccountId> trailingTooLong =
        trailingSince(firstUnprojected, startedAt.minus(allowedFreshness));
    for (AccountId account : trailingTooLong) {
      ReadModelBalance readModel = projected.get(account);
      Money projectedBalance = readModel == null ? Money.ofPence(0) : readModel.balance();
      Money writeSide = Objects.requireNonNull(written.get(account)).balance();
      drifts.put(account, writeSide.plus(projectedBalance.negate()));
    }
    return new ReadModelComparison(drifts, trailing, trailingTooLong);
  }

  /**
   * The Accounts whose first Entry the Read Model has not applied, at the entry_seq given, was
   * booked before the moment.
   */
  private Set<AccountId> trailingSince(Map<AccountId, Long> firstUnprojected, Instant moment) {
    if (firstUnprojected.isEmpty()) {
      return Set.of();
    }
    Set<AccountId> trailing = new HashSet<>();
    jdbc.sql(
            """
            select account_id, entry_seq
            from entry
            where account_id in (:accountIds)
              and entry_seq in (:entrySeqs)
              and booked_at < :moment
            """)
        .param("accountIds", firstUnprojected.keySet().stream().map(AccountId::value).toList())
        .param("entrySeqs", firstUnprojected.values().stream().distinct().toList())
        .param("moment", moment.atOffset(ZoneOffset.UTC))
        .query(
            (RowCallbackHandler)
                rs -> {
                  AccountId account = new AccountId(rs.getObject("account_id", UUID.class));
                  if (firstUnprojected.get(account) == rs.getLong("entry_seq")) {
                    trailing.add(account);
                  }
                });
    return trailing;
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

  /**
   * The Accounts whose balance row was last changed by the transaction with this id or a later one.
   * A posting changes the row before it commits, possibly long before, so a time stamp on the row
   * can't say whether the last run saw it; the transaction id can. Every transaction older than the
   * id had ended when the last run started, so the last run saw its changes. Ids are compared by
   * age, as Postgres compares them, so this holds across transaction id wraparound.
   */
  private List<AccountId> touchedSince(long transactionId) {
    return jdbc.sql(
            """
            select account_id
            from account_balance
            where age(xmin) <= age((:transactionId % 4294967296)::text::xid)
            """)
        .param("transactionId", transactionId)
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

  /** Every Account with a balance row, which is every Account with an Entry. */
  private List<AccountId> allAccountsWithBalances() {
    return jdbc.sql("select account_id from account_balance")
        .query((rs, rowNum) -> new AccountId(rs.getObject("account_id", UUID.class)))
        .list();
  }

  /** The stored Balance of each of the Accounts, with the last entry_seq handed out. */
  private Map<AccountId, WriteSideBalance> writeSideBalancesOf(Collection<AccountId> accounts) {
    if (accounts.isEmpty()) {
      return Map.of();
    }
    return jdbc
        .sql(
            """
            select account_id, balance_pence, last_entry_seq
            from account_balance
            where account_id in (:accountIds)
            """)
        .param("accountIds", accounts.stream().map(AccountId::value).toList())
        .query((rs, rowNum) ->
            Map.entry(
                new AccountId(rs.getObject("account_id", UUID.class)),
                new WriteSideBalance(
                    Money.ofPence(rs.getLong("balance_pence")), rs.getLong("last_entry_seq"))))
        .list()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /**
   * The balance_after of the Entries at the Read Model's entry_seq of each Account, by Account and
   * entry_seq. It reads a few more Entries than asked for, which the caller ignores.
   */
  private Map<AccountId, Map<Long, Money>> balancesAfter(Map<AccountId, ReadModelBalance> behind) {
    if (behind.isEmpty()) {
      return Map.of();
    }
    Map<AccountId, Map<Long, Money>> balances = new HashMap<>();
    jdbc.sql(
            """
            select account_id, entry_seq, balance_after_pence
            from entry
            where account_id in (:accountIds)
              and entry_seq in (:entrySeqs)
            """)
        .param("accountIds", behind.keySet().stream().map(AccountId::value).toList())
        .param(
            "entrySeqs",
            behind.values().stream().map(ReadModelBalance::lastEntrySeq).distinct().toList())
        .query(
            (RowCallbackHandler)
                rs ->
                    balances
                        .computeIfAbsent(
                            new AccountId(rs.getObject("account_id", UUID.class)),
                            id -> new HashMap<>())
                        .put(
                            rs.getLong("entry_seq"),
                            Money.ofPence(rs.getLong("balance_after_pence"))));
    return balances;
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

  /** When a run started, and the oldest transaction id still running then. */
  private record RunStart(Instant startedAt, long xmin) {}

  /** An Account's stored Balance and the last entry_seq handed out. */
  private record WriteSideBalance(Money balance, long lastEntrySeq) {}

  /**
   * How far each drifting Read Model Balance is from the write side, the Accounts the Read Model
   * trails, and those it has trailed for longer than the allowed Projection Freshness.
   */
  private record ReadModelComparison(
      Map<AccountId, Money> drifts, Set<AccountId> trailing, Set<AccountId> trailingTooLong) {}

  /** What a run found; before the first run, NaN gauges with a last run at the epoch. */
  private record Result(Instant startedAt, long imbalancePence, int accountsTrailingTooLong) {

    static final Result NOT_RUN = new Result(Instant.EPOCH, 0, 0);

    double balanced() {
      if (this == NOT_RUN) {
        return Double.NaN;
      }
      return imbalancePence == 0 && accountsTrailingTooLong == 0 ? 1 : 0;
    }

    double imbalance() {
      return this == NOT_RUN ? Double.NaN : imbalancePence;
    }

    double epochSeconds() {
      return startedAt.getEpochSecond() + startedAt.getNano() / 1e9;
    }
  }
}
