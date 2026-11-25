package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The second job of the balanced check: that each Account's stored Balance agrees with the Σ of its
 * Entries, for the Accounts touched since the last run, and for every Account in the nightly sweep.
 * The SQL sums Entries as Debits minus Credits, which needs no Normal Side; the Normal Sides come
 * from the accounts module's public API, never its table (CODING_STANDARDS rule 14).
 */
@Component
class StoredBalances {

  private final JdbcClient jdbc;
  private final Accounts accounts;

  StoredBalances(JdbcClient jdbc, Accounts accounts) {
    this.jdbc = jdbc;
    this.accounts = accounts;
  }

  /**
   * The Accounts whose balance row was last changed by the transaction with this id or a later one.
   * A posting changes the row before it commits, possibly long before, so a time stamp on the row
   * can't say whether the last run saw it; the transaction id can. Every transaction older than the
   * id had ended when the last run started, so the last run saw its changes. Ids are compared by
   * age, as Postgres compares them, so this holds across transaction id wraparound.
   */
  List<AccountId> touchedSince(long transactionId) {
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

  /** Every Account with a balance row, which is every Account with an Entry. */
  List<AccountId> allAccounts() {
    return jdbc.sql("select account_id from account_balance")
        .query((rs, rowNum) -> new AccountId(rs.getObject("account_id", UUID.class)))
        .list();
  }

  /**
   * How far each of the Accounts' stored Balance is from the Σ of its Entries, on its Normal Side,
   * for the Accounts where they differ.
   */
  Map<AccountId, Money> driftsOf(Collection<AccountId> accountIds) {
    if (accountIds.isEmpty()) {
      return Map.of();
    }
    List<Sums> sums =
        jdbc.sql(
                """
                select b.account_id, b.balance_pence,
                       coalesce(sum(case when e.side = 'DEBIT' then e.amount_pence
                                         else -e.amount_pence end), 0) as debits_minus_credits
                from account_balance b
                left join entry e on e.account_id = b.account_id
                where b.account_id = any(:accountIds)
                group by b.account_id, b.balance_pence
                """)
            .param("accountIds", accountIds.stream().map(AccountId::value).toArray(UUID[]::new))
            .query(StoredBalances::sums)
            .list();
    return driftsIn(sums);
  }

  /** {@link #driftsOf} over every Account. */
  Map<AccountId, Money> allDrifts() {
    List<Sums> sums =
        jdbc.sql(
                """
                select b.account_id, b.balance_pence,
                       coalesce(sum(case when e.side = 'DEBIT' then e.amount_pence
                                         else -e.amount_pence end), 0) as debits_minus_credits
                from account_balance b
                left join entry e on e.account_id = b.account_id
                group by b.account_id, b.balance_pence
                """)
            .query(StoredBalances::sums)
            .list();
    return driftsIn(sums);
  }

  private Map<AccountId, Money> driftsIn(List<Sums> sums) {
    Map<AccountId, Side> normalSides =
        accounts.normalSidesOf(sums.stream().map(Sums::account).toList());
    Map<AccountId, Money> drifts = new HashMap<>();
    for (Sums sum : sums) {
      Side normalSide = normalSides.get(sum.account());
      if (normalSide == null) {
        throw new IllegalStateException(
            "Account " + sum.account() + " has a balance row but no Account");
      }
      long entries =
          normalSide == Side.DEBIT ? sum.debitsMinusCredits() : -sum.debitsMinusCredits();
      long drift = sum.storedPence() - entries;
      if (drift != 0) {
        drifts.put(sum.account(), Money.ofPence(drift));
      }
    }
    return drifts;
  }

  private static Sums sums(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new Sums(
        new AccountId(rs.getObject("account_id", UUID.class)),
        rs.getLong("balance_pence"),
        rs.getLong("debits_minus_credits"));
  }

  /** An Account's stored Balance and the Σ of its Entries as Debits minus Credits. */
  private record Sums(AccountId account, long storedPence, long debitsMinusCredits) {}
}
