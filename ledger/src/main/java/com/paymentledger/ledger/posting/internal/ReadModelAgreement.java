package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.posting.ReadModelBalance;
import com.paymentledger.ledger.posting.ReadModelBalances;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The third job of the balanced check: that each Account's Read Model Balance agrees with the
 * write-side Balance at the same entry_seq. The Read Model is asked through the {@link
 * ReadModelBalances} port, which views implements (ADR 0012).
 *
 * <p>The grace period is the longest the Read Model may leave an Account's Entry unapplied before
 * the check counts the Account as drift. It is per Account and measured from the Entry's Booking
 * Time; it is not the Projection Freshness gauge, which is how far the Read Model trails the ledger
 * as a whole.
 */
@Component
class ReadModelAgreement {

  private static final Logger log = LoggerFactory.getLogger(ReadModelAgreement.class);

  private final JdbcClient jdbc;
  private final ReadModelBalances readModelBalances;
  private final Duration grace;

  ReadModelAgreement(
      JdbcClient jdbc,
      ReadModelBalances readModelBalances,
      @Value("${ledger.balanced-check.read-model-grace:PT60S}") Duration grace) {
    this.jdbc = jdbc;
    this.readModelBalances = readModelBalances;
    this.grace = grace;
  }

  /**
   * Compares each Account's Read Model Balance with the write-side Balance after the same
   * entry_seq, so a Read Model that only trails the ledger is no drift. An Account the Read Model
   * trails is returned to be checked again once it catches up; so is every Account when the Read
   * Model can't be read. An Account whose oldest unprojected Entry was booked longer than the grace
   * period before the run started is drift, by how far the Read Model Balance is from the
   * write-side Balance.
   */
  ReadModelComparison compare(Set<AccountId> accounts, Instant startedAt) {
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
    Set<AccountId> trailingBeyondGrace = trailingSince(firstUnprojected, startedAt.minus(grace));
    for (AccountId account : trailingBeyondGrace) {
      ReadModelBalance readModel = projected.get(account);
      Money projectedBalance = readModel == null ? Money.ofPence(0) : readModel.balance();
      Money writeSide = Objects.requireNonNull(written.get(account)).balance();
      drifts.put(account, writeSide.plus(projectedBalance.negate()));
    }
    return new ReadModelComparison(drifts, trailing, trailingBeyondGrace);
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

  /**
   * How far each drifting Read Model Balance is from the write side, the Accounts the Read Model
   * trails, and those it has trailed for longer than the grace period.
   */
  record ReadModelComparison(
      Map<AccountId, Money> drifts, Set<AccountId> trailing, Set<AccountId> trailingBeyondGrace) {}

  /** An Account's stored Balance and the last entry_seq handed out. */
  private record WriteSideBalance(Money balance, long lastEntrySeq) {}
}
