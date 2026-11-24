package com.paymentledger.ledger.views.internal;

import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.posting.ReadModelBalance;
import com.paymentledger.ledger.posting.ReadModelBalances;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import com.paymentledger.ledger.shared.TransactionId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the Read Model, which the projector builds from the ledger's Kafka events. */
@Repository
class JdbcReadModel implements ReadModelBalances {

  private final JdbcClient jdbc;

  JdbcReadModel(@Qualifier(ReadModelConnection.NAME) DataSource readModel) {
    this.jdbc = JdbcClient.create(readModel);
  }

  /** The Read Model Balance of each Account that has one; an Account with no Entries has none. */
  Map<AccountId, Money> balancesOf(Collection<AccountId> accounts) {
    if (accounts.isEmpty()) {
      return Map.of();
    }
    return jdbc
        .sql(
            """
            select account_id, balance_pence
            from account_balance_view
            where account_id in (:accountIds)
            """)
        .param("accountIds", accounts.stream().map(AccountId::value).toList())
        .query((rs, rowNum) ->
            Map.entry(
                new AccountId(rs.getObject("account_id", UUID.class)),
                Money.ofPence(rs.getLong("balance_pence"))))
        .list()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  @Override
  public Map<AccountId, ReadModelBalance> readModelBalancesOf(Collection<AccountId> accounts) {
    if (accounts.isEmpty()) {
      return Map.of();
    }
    return jdbc
        .sql(
            """
            select account_id, balance_pence, last_entry_seq
            from account_balance_view
            where account_id in (:accountIds)
            """)
        .param("accountIds", accounts.stream().map(AccountId::value).toList())
        .query((rs, rowNum) ->
            Map.entry(
                new AccountId(rs.getObject("account_id", UUID.class)),
                new ReadModelBalance(
                    Money.ofPence(rs.getLong("balance_pence")), rs.getLong("last_entry_seq"))))
        .list()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /**
   * Up to limit of the Account's Statement Lines booked from (inclusive) to (exclusive), newest
   * first by Booking Time and entry_seq, starting after the cursor. A null bound or cursor doesn't
   * limit the page.
   */
  List<StatementLine> statementPage(
      AccountId account,
      @Nullable Instant from,
      @Nullable Instant to,
      @Nullable StatementCursor after,
      int limit) {
    return jdbc.sql(
            """
            select transaction_id, booked_at, entry_seq, side, amount_pence,
                   balance_after_pence, counterparty_account_id
            from statement_line
            where account_id = :accountId
              and booked_at >= coalesce(cast(:from as timestamptz), '-infinity')
              and booked_at < coalesce(cast(:to as timestamptz), 'infinity')
              and (booked_at, entry_seq) < (
                  coalesce(cast(:afterBookedAt as timestamptz), 'infinity'),
                  coalesce(cast(:afterEntrySeq as bigint), 0))
            order by booked_at desc, entry_seq desc
            limit :limit
            """)
        .param("accountId", account.value())
        .param("from", utc(from))
        .param("to", utc(to))
        .param("afterBookedAt", after == null ? null : utc(after.bookingTime()))
        .param("afterEntrySeq", after == null ? null : after.entrySeq())
        .param("limit", limit)
        .query((rs, rowNum) -> {
          UUID counterparty = rs.getObject("counterparty_account_id", UUID.class);
          return new StatementLine(
              new TransactionId(rs.getObject("transaction_id", UUID.class)),
              rs.getObject("booked_at", OffsetDateTime.class).toInstant(),
              rs.getLong("entry_seq"),
              Side.valueOf(rs.getString("side")),
              Money.ofPence(rs.getLong("amount_pence")),
              Money.ofPence(rs.getLong("balance_after_pence")),
              counterparty == null ? null : new AccountId(counterparty));
        })
        .list();
  }

  /**
   * The dashboard's per-minute figures summed from the minute since (inclusive) on: how many
   * Payments were made in the minute given, and how many became Completed and Rejected, by
   * Rejection Reason. A Payment is made when it first takes a status: Pending for a Faster Payment
   * accepted for sending, and otherwise its final status, Insufficient Funds included.
   */
  PaymentFigures paymentFigures(Instant since, Instant madeIn) {
    return jdbc.sql(
            """
            select
                coalesce(sum(payments) filter (
                    where minute = :madeIn
                      and (status = 'PENDING' or payment_type = 'INTERNAL_TRANSFER'
                           or rejection_reason = 'INSUFFICIENT_FUNDS')), 0) as made,
                coalesce(sum(payments) filter (where status = 'COMPLETED'), 0) as completed,
                coalesce(sum(payments) filter (where status = 'REJECTED'), 0) as rejected,
                coalesce(sum(payments) filter (
                    where rejection_reason = 'INSUFFICIENT_FUNDS'), 0) as insufficient_funds,
                coalesce(sum(payments) filter (
                    where rejection_reason = 'SCHEME_REJECTED'), 0) as scheme_rejected
            from dashboard_minute
            where minute >= :since
            """)
        .param("since", utc(since))
        .param("madeIn", utc(madeIn))
        .query((rs, rowNum) ->
            new PaymentFigures(
                rs.getLong("made"),
                rs.getLong("completed"),
                rs.getLong("rejected"),
                Map.of(
                    RejectionReason.INSUFFICIENT_FUNDS, rs.getLong("insufficient_funds"),
                    RejectionReason.SCHEME_REJECTED, rs.getLong("scheme_rejected"))))
        .single();
  }

  /**
   * How many Payments were made in one minute, and how many became Completed and Rejected, by
   * Rejection Reason, over a longer stretch.
   */
  record PaymentFigures(
      long madeInMinute, long completed, long rejected, Map<RejectionReason, Long> rejections) {}

  private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
