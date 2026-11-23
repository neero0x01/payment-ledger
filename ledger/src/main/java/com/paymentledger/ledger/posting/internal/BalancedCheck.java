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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * until it no longer is, so the gauges keep reporting drift that nothing touches again.
 */
@Component
class BalancedCheck implements MeterBinder {

  /**
   * How far back before the last run's start a touch still counts: a posting stamps its balance row
   * before it commits, so a commit landing just after a run started is caught by the next.
   */
  private static final Duration COMMIT_SLACK = Duration.ofSeconds(5);

  private static final Logger log = LoggerFactory.getLogger(BalancedCheck.class);

  private final JdbcClient jdbc;
  private final ReadModelBalances readModelBalances;
  private volatile Result last = Result.NOT_RUN;
  private @Nullable Instant lastStartedAt;

  /** Accounts the last run found drifting, or trailed by the Read Model: checked on every run. */
  private Set<AccountId> unproven = Set.of();

  BalancedCheck(JdbcClient jdbc, ReadModelBalances readModelBalances) {
    this.jdbc = jdbc;
    this.readModelBalances = readModelBalances;
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
    Set<AccountId> accounts;
    Map<AccountId, Money> storedDrifts;
    if (lastStartedAt == null) {
      accounts = Set.copyOf(allAccountsWithBalances());
      storedDrifts = allStoredBalanceDrifts();
    } else {
      accounts = new HashSet<>(unproven);
      accounts.addAll(touchedSince(lastStartedAt.minus(COMMIT_SLACK)));
      storedDrifts = storedBalanceDrifts(accounts);
    }
    ReadModelComparison readModel = compareWithReadModel(accounts);
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
    lastStartedAt = startedAt;
    last = new Result(startedAt, imbalance);
  }

  /**
   * Compares each Account's Read Model Balance with the write-side Balance after the same
   * entry_seq, so a Read Model that only trails the ledger is no drift. An Account the Read Model
   * trails is returned to be checked again once it catches up; so is every Account when the Read
   * Model can't be read.
   */
  private ReadModelComparison compareWithReadModel(Set<AccountId> accounts) {
    Map<AccountId, ReadModelBalance> projected;
    try {
      projected = readModelBalances.readModelBalancesOf(accounts);
    } catch (DataAccessException e) {
      log.warn("The balanced check could not read the Read Model: {}", e.getMessage());
      return new ReadModelComparison(Map.of(), accounts);
    }
    Map<AccountId, WriteSideBalance> written = writeSideBalancesOf(accounts);
    Map<AccountId, Money> drifts = new HashMap<>();
    Set<AccountId> trailing = new HashSet<>();
    Map<AccountId, ReadModelBalance> behind = new HashMap<>();
    for (AccountId account : accounts) {
      ReadModelBalance readModel = projected.get(account);
      WriteSideBalance writeSide = written.get(account);
      if (writeSide == null) {
        continue;
      }
      if (readModel == null) {
        trailing.add(account);
      } else if (readModel.lastEntrySeq() >= writeSide.lastEntrySeq()) {
        // Ahead of the ledger is drift too: the Read Model holds an Entry the ledger never posted.
        Money drift = readModel.balance().plus(writeSide.balance().negate());
        if (drift.pence() != 0 || readModel.lastEntrySeq() > writeSide.lastEntrySeq()) {
          drifts.put(account, drift);
        }
      } else {
        trailing.add(account);
        behind.put(account, readModel);
      }
    }
    Map<AccountId, Map<Long, Money>> balancesAfter = balancesAfter(behind);
    behind.forEach((account, readModel) -> {
      Money writeSide = balancesAfter.getOrDefault(account, Map.of()).get(readModel.lastEntrySeq());
      Money drift =
          writeSide == null ? readModel.balance() : readModel.balance().plus(writeSide.negate());
      if (drift.pence() != 0) {
        drifts.put(account, drift);
      }
    });
    return new ReadModelComparison(drifts, trailing);
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

  /** An Account's stored Balance and the last entry_seq handed out. */
  private record WriteSideBalance(Money balance, long lastEntrySeq) {}

  /**
   * How far each drifting Read Model Balance is from the write side, and the Accounts the Read
   * Model trails.
   */
  private record ReadModelComparison(Map<AccountId, Money> drifts, Set<AccountId> trailing) {}

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
