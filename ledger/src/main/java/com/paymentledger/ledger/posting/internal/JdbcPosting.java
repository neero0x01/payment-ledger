package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.TransactionId;
import java.sql.Types;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcPosting implements Posting {

  private final JdbcClient jdbc;
  private final Accounts accounts;

  JdbcPosting(JdbcClient jdbc, Accounts accounts) {
    this.jdbc = jdbc;
    this.accounts = accounts;
  }

  @Override
  @Transactional
  public TransactionId post(NewTransaction transaction) {
    UUID transactionId =
        jdbc.sql(
                """
                insert into ledger_transaction (kind, booked_at, payment_id)
                values (:kind, clock_timestamp(), :paymentId)
                returning id
                """)
            .param("kind", transaction.kind().name())
            .param(
                "paymentId", transaction.payment().map(PaymentId::value).orElse(null), Types.OTHER)
            .query(UUID.class)
            .single();
    for (Entry entry : transaction.entries()) {
      insertEntry(transactionId, entry);
      applyToBalance(entry);
    }
    return new TransactionId(transactionId);
  }

  private void insertEntry(UUID transactionId, Entry entry) {
    jdbc.sql(
            """
            insert into entry (transaction_id, account_id, side, amount_pence, booked_at)
            select id, :accountId, :side, :amountPence, booked_at
            from ledger_transaction
            where id = :transactionId
            """)
        .param("transactionId", transactionId)
        .param("accountId", entry.account().value())
        .param("side", entry.side().name())
        .param("amountPence", entry.amount().pence())
        .update();
  }

  private void applyToBalance(Entry entry) {
    Account account =
        accounts
            .find(entry.account())
            .orElseThrow(() -> new IllegalArgumentException("Unknown Account " + entry.account()));
    jdbc.sql(
            """
            insert into account_balance (account_id, balance_pence)
            values (:accountId, :change)
            on conflict (account_id) do update
            set balance_pence = account_balance.balance_pence + excluded.balance_pence,
                updated_at = clock_timestamp()
            """)
        .param("accountId", account.id().value())
        .param("change", entry.balanceChange(account.normalSide()).pence())
        .update();
  }
}
