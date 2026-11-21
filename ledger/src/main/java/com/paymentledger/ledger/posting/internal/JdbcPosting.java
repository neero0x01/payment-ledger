package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.posting.CorrectionDetails;
import com.paymentledger.ledger.posting.CorrectionReason;
import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.posting.TransactionKind;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.Side;
import com.paymentledger.ledger.shared.TransactionId;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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
                insert into ledger_transaction (
                    kind, booked_at, payment_id, reverses_transaction_id,
                    correction_reason, correction_note)
                values (
                    :kind, clock_timestamp(), :paymentId, :reversesTransactionId,
                    :correctionReason, :correctionNote)
                returning id
                """)
            .param("kind", transaction.kind().name())
            .param(
                "paymentId", transaction.payment().map(PaymentId::value).orElse(null), Types.OTHER)
            .param(
                "reversesTransactionId",
                transaction.reverses().map(TransactionId::value).orElse(null),
                Types.OTHER)
            .param(
                "correctionReason",
                transaction.correction().map(c -> c.reason().name()).orElse(null),
                Types.VARCHAR)
            .param(
                "correctionNote",
                transaction.correction().map(CorrectionDetails::note).orElse(null),
                Types.VARCHAR)
            .query(UUID.class)
            .single();
    for (Entry entry : transaction.entries()) {
      insertEntry(transactionId, entry);
      applyToBalance(entry);
    }
    return new TransactionId(transactionId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PostedTransaction> find(TransactionId id) {
    List<Entry> entries =
        jdbc.sql(
                """
                select account_id, side, amount_pence
                from entry
                where transaction_id = :id
                order by id
                """)
            .param("id", id.value())
            .query((rs, rowNum) ->
                new Entry(
                    new AccountId(rs.getObject("account_id", UUID.class)),
                    Side.valueOf(rs.getString("side")),
                    Money.ofPence(rs.getLong("amount_pence"))))
            .list();
    return jdbc.sql(
            """
            select id, kind, booked_at, payment_id, reverses_transaction_id,
                   correction_reason, correction_note
            from ledger_transaction
            where id = :id
            """)
        .param("id", id.value())
        .query((rs, rowNum) -> {
          UUID paymentId = rs.getObject("payment_id", UUID.class);
          UUID reverses = rs.getObject("reverses_transaction_id", UUID.class);
          String reason = rs.getString("correction_reason");
          return new PostedTransaction(
              new TransactionId(rs.getObject("id", UUID.class)),
              TransactionKind.valueOf(rs.getString("kind")),
              rs.getObject("booked_at", OffsetDateTime.class).toInstant(),
              paymentId == null ? null : new PaymentId(paymentId),
              reverses == null ? null : new TransactionId(reverses),
              reason == null
                  ? null
                  : new CorrectionDetails(
                      CorrectionReason.valueOf(reason), rs.getString("correction_note")),
              entries);
        })
        .optional();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isReversed(TransactionId transaction) {
    return jdbc.sql(
            """
            select exists (
                select 1 from ledger_transaction where reverses_transaction_id = :id
            )
            """)
        .param("id", transaction.value())
        .query(Boolean.class)
        .single();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Money> balanceOf(AccountId account) {
    if (accounts.find(account).isEmpty()) {
      return Optional.empty();
    }
    long pence =
        jdbc.sql("select balance_pence from account_balance where account_id = :accountId")
            .param("accountId", account.value())
            .query(Long.class)
            .optional()
            .orElse(0L);
    return Optional.of(Money.ofPence(pence));
  }

  @Override
  @Transactional(readOnly = true)
  public List<TransactionId> transactionsOf(PaymentId payment) {
    return jdbc
        .sql(
            """
            select id
            from ledger_transaction
            where payment_id = :paymentId
            order by booked_at, id
            """)
        .param("paymentId", payment.value())
        .query(UUID.class)
        .list()
        .stream()
        .map(TransactionId::new)
        .toList();
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
