package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.outbox.Outbox;
import com.paymentledger.ledger.posting.CorrectionDetails;
import com.paymentledger.ledger.posting.CorrectionReason;
import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.KeyedCorrection;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.PostedEntry;
import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.posting.TransactionKind;
import com.paymentledger.ledger.posting.TransactionSource;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.IdempotentOutcome;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.Problem;
import com.paymentledger.ledger.shared.RequestHash;
import com.paymentledger.ledger.shared.Side;
import com.paymentledger.ledger.shared.TransactionId;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcPosting implements Posting {

  /** Postgres orders uuids by their bytes, as unsigned numbers. */
  private static final Comparator<AccountId> IN_DATABASE_ORDER =
      Comparator.comparing(
          AccountId::value,
          (UUID x, UUID y) -> {
            int high = Long.compareUnsigned(x.getMostSignificantBits(), y.getMostSignificantBits());
            return high != 0
                ? high
                : Long.compareUnsigned(x.getLeastSignificantBits(), y.getLeastSignificantBits());
          });

  private final JdbcClient jdbc;
  private final Accounts accounts;
  private final Outbox outbox;

  JdbcPosting(JdbcClient jdbc, Accounts accounts, Outbox outbox) {
    this.jdbc = jdbc;
    this.accounts = accounts;
    this.outbox = outbox;
  }

  /**
   * Locks the balance rows of every Account the Transaction touches before inserting it, so its
   * Booking Time, read from clock_timestamp() below, comes after the locks (ADR 0004).
   */
  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public TransactionId post(NewTransaction transaction) {
    return postUnlessKeyUsed(transaction, null).orElseThrow();
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public IdempotentOutcome<TransactionId> postCorrection(
      NewTransaction correction, KeyedCorrection keyed) {
    return postUnlessKeyUsed(correction, keyed)
        .map(IdempotentOutcome::first)
        .orElseGet(() -> IdempotentOutcome.replay(correctionPostedWith(keyed).orElseThrow()));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TransactionId> correctionPostedWith(KeyedCorrection keyed) {
    Optional<CorrectionWithKey> found =
        jdbc.sql(
                """
                select id, request_hash
                from ledger_transaction
                where principal_id = :principal and idempotency_key = :key
                """)
            .param("principal", keyed.operator().value())
            .param("key", keyed.key().value())
            .query((rs, rowNum) ->
                new CorrectionWithKey(
                    new TransactionId(rs.getObject("id", UUID.class)),
                    new RequestHash(rs.getString("request_hash"))))
            .optional();
    found.ifPresent(correction ->
        correction.requestHash().requireSameRequest(keyed.requestHash(), "Correction"));
    return found.map(CorrectionWithKey::id);
  }

  /** A Correction posted with an Idempotency-Key, and the hash of the request that posted it. */
  private record CorrectionWithKey(TransactionId id, RequestHash requestHash) {}

  /**
   * Locks the balance rows first, then inserts the Transaction unless its Idempotency-Key was
   * already used. The Booking Time is on the inserted row and must come from clock_timestamp()
   * after the locks (ADR 0004), so a keyed Correction locks before its keyed insert, unlike a
   * Payment (ADR 0007). A retry of a Correction in flight takes its locks before it reaches the
   * unique index, in ascending account id order like every posting, so an identical retry queues
   * behind the first request on their first shared row. A request it waits on at the index already
   * holds all its locks and never waits back, so this can't deadlock. When nothing is inserted the
   * locks are released at commit having changed nothing. A posted Transaction appends its
   * TransactionPosted event to the outbox in the same database transaction (ADR 0005).
   */
  private Optional<TransactionId> postUnlessKeyUsed(
      NewTransaction transaction, @Nullable KeyedCorrection keyed) {
    lockBalances(transaction.entries().stream().map(Entry::account).collect(Collectors.toSet()));
    CorrectionFields correction = CorrectionFields.of(transaction.correction());
    Optional<InsertedTransaction> inserted =
        jdbc.sql(
                """
                insert into ledger_transaction (
                    kind, booked_at, payment_id, reverses_transaction_id,
                    correction_reason, correction_note,
                    principal_id, idempotency_key, request_hash)
                values (
                    :kind, clock_timestamp(), :paymentId, :reversesTransactionId,
                    :correctionReason, :correctionNote,
                    :principal, :key, :requestHash)
                on conflict (principal_id, idempotency_key) where idempotency_key is not null
                do nothing
                returning id, booked_at
                """)
            .param("kind", transaction.kind().name())
            .param(
                "paymentId", transaction.payment().map(PaymentId::value).orElse(null), Types.OTHER)
            .param(
                "reversesTransactionId",
                transaction.reverses().map(TransactionId::value).orElse(null),
                Types.OTHER)
            .param("correctionReason", correction.reason(), Types.VARCHAR)
            .param("correctionNote", correction.note(), Types.VARCHAR)
            .param("principal", keyed == null ? null : keyed.operator().value(), Types.OTHER)
            .param("key", keyed == null ? null : keyed.key().value(), Types.VARCHAR)
            .param("requestHash", keyed == null ? null : keyed.requestHash().value(), Types.VARCHAR)
            .query((rs, rowNum) ->
                new InsertedTransaction(
                    new TransactionId(rs.getObject("id", UUID.class)),
                    rs.getObject("booked_at", OffsetDateTime.class).toInstant()))
            .optional();
    if (inserted.isEmpty()) {
      return Optional.empty();
    }
    TransactionId transactionId = inserted.get().id();
    List<PostedEntry> posted = new ArrayList<>();
    for (Entry entry : transaction.entries()) {
      BalanceRow after = applyToBalance(entry);
      insertEntry(transactionId.value(), entry, after);
      posted.add(new PostedEntry(entry, after.lastEntrySeq(), after.balance()));
    }
    outbox.append(
        TransactionPostedEvent.of(
            transactionId, transaction, inserted.get().bookingTime(), posted));
    return Optional.of(transactionId);
  }

  /** A Transaction row just inserted, with the Booking Time the database gave it. */
  private record InsertedTransaction(TransactionId id, Instant bookingTime) {}

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Map<AccountId, Money> lockBalances(Set<AccountId> accounts) {
    Map<AccountId, Money> balances = new LinkedHashMap<>();
    accounts.stream()
        .sorted(IN_DATABASE_ORDER)
        .forEach(account -> balances.put(account, lockBalance(account)));
    return balances;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PostedTransaction> find(TransactionId id) {
    List<PostedEntry> entries =
        jdbc.sql(
                """
                select account_id, side, amount_pence, entry_seq, balance_after_pence
                from entry
                where transaction_id = :id
                order by id
                """)
            .param("id", id.value())
            .query((rs, rowNum) ->
                new PostedEntry(
                    new Entry(
                        new AccountId(rs.getObject("account_id", UUID.class)),
                        Side.valueOf(rs.getString("side")),
                        Money.ofPence(rs.getLong("amount_pence"))),
                    rs.getLong("entry_seq"),
                    Money.ofPence(rs.getLong("balance_after_pence"))))
            .list();
    return jdbc.sql(
            """
            select id, kind, booked_at, payment_id, reverses_transaction_id,
                   correction_reason, correction_note, source
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
              TransactionSource.valueOf(rs.getString("source")),
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
  public Optional<TransactionId> reversalOf(TransactionId transaction) {
    return jdbc.sql("select id from ledger_transaction where reverses_transaction_id = :id")
        .param("id", transaction.value())
        .query((rs, row) -> new TransactionId(rs.getObject("id", UUID.class)))
        .optional();
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
  public Optional<Money> balanceOf(AccountId account, Instant asAt) {
    if (accounts.find(account).isEmpty()) {
      return Optional.empty();
    }
    OffsetDateTime moment = asAt.atOffset(ZoneOffset.UTC);
    boolean inTheFuture =
        jdbc.sql("select :asAt > clock_timestamp()")
            .param("asAt", moment)
            .query(Boolean.class)
            .single();
    if (inTheFuture) {
      throw Problem.validationFailed("asAt " + asAt + " is in the future");
    }
    long pence =
        jdbc.sql(
                """
                select balance_after_pence
                from entry
                where account_id = :accountId and booked_at <= :asAt
                order by booked_at desc, entry_seq desc
                limit 1
                """)
            .param("accountId", account.value())
            .param("asAt", moment)
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

  private void insertEntry(UUID transactionId, Entry entry, BalanceRow after) {
    jdbc.sql(
            """
            insert into entry (
                transaction_id, account_id, side, amount_pence, booked_at,
                entry_seq, balance_after_pence)
            select id, :accountId, :side, :amountPence, booked_at, :entrySeq, :balanceAfterPence
            from ledger_transaction
            where id = :transactionId
            """)
        .param("transactionId", transactionId)
        .param("accountId", entry.account().value())
        .param("side", entry.side().name())
        .param("amountPence", entry.amount().pence())
        .param("entrySeq", after.lastEntrySeq())
        .param("balanceAfterPence", after.balance().pence())
        .update();
  }

  /**
   * Locks the Account's balance row until the database transaction ends, first creating it at zero
   * if the Account has never had an Entry, and returns its Balance.
   */
  private Money lockBalance(AccountId account) {
    createBalanceRowIfMissing(account);
    long pence =
        jdbc.sql(
                """
                select balance_pence
                from account_balance
                where account_id = :accountId
                for update
                """)
            .param("accountId", account.value())
            .query(Long.class)
            .single();
    return Money.ofPence(pence);
  }

  /** A row inserted by a concurrent transaction makes this wait for it, then do nothing. */
  private void createBalanceRowIfMissing(AccountId account) {
    jdbc.sql(
            """
            insert into account_balance (account_id, balance_pence)
            values (:accountId, 0)
            on conflict (account_id) do nothing
            """)
        .param("accountId", account.value())
        .update();
  }

  /** Moves the Account's locked Balance by the Entry and hands out its next entry_seq. */
  private BalanceRow applyToBalance(Entry entry) {
    Account account =
        accounts
            .find(entry.account())
            .orElseThrow(() -> new IllegalArgumentException("Unknown Account " + entry.account()));
    return jdbc.sql(
            """
            update account_balance
            set balance_pence = balance_pence + :change,
                last_entry_seq = last_entry_seq + 1,
                updated_at = clock_timestamp()
            where account_id = :accountId
            returning balance_pence, last_entry_seq
            """)
        .param("accountId", account.id().value())
        .param("change", entry.balanceChange(account.normalSide()).pence())
        .query((rs, rowNum) ->
            new BalanceRow(
                Money.ofPence(rs.getLong("balance_pence")), rs.getLong("last_entry_seq")))
        .single();
  }

  /** An Account's balance row: its Balance and the last entry_seq handed out. */
  private record BalanceRow(Money balance, long lastEntrySeq) {}
}
