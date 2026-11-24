package com.paymentledger.projector;

import com.paymentledger.projector.TransactionPosted.PostedEntry;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The Read Model in the readmodel database, which only the projector writes. */
@Repository
class ReadModel {

  private final JdbcClient jdbc;

  ReadModel(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Applies a TransactionPosted event, a Statement Line per Entry and each Account's Balance,
   * unless an event with its eventId was applied already.
   */
  @Transactional
  void apply(TransactionPosted event) {
    if (!recordProcessed(event.eventId())) {
      return;
    }
    for (PostedEntry entry : event.payload().entries()) {
      insertStatementLine(event, entry);
      updateBalance(entry);
    }
  }

  /**
   * Applies a PaymentStatusChanged event, adding its Payment to the dashboard's figures for the
   * minute it happened in, unless an event with its eventId was applied already. Counts add up the
   * same in any order.
   */
  @Transactional
  void apply(PaymentStatusChanged event) {
    if (!recordProcessed(event.eventId())) {
      return;
    }
    PaymentStatusChanged.Payload payment = event.payload();
    jdbc.sql(
            """
            insert into dashboard_minute (
                minute, payment_type, status, rejection_reason, payments, value_pence)
            values (date_trunc('minute', :occurredAt), :type, :status, :rejectionReason, 1,
                    :amountPence)
            on conflict (minute, payment_type, status, rejection_reason) do update
            set payments = dashboard_minute.payments + 1,
                value_pence = dashboard_minute.value_pence + excluded.value_pence
            """)
        .param("occurredAt", event.occurredAt().atOffset(ZoneOffset.UTC))
        .param("type", payment.type())
        .param("status", payment.status())
        .param("rejectionReason", payment.rejectionReason() == null ? "" : payment.rejectionReason())
        .param("amountPence", payment.amountPence())
        .update();
  }

  /** Records the event as processed, or answers false if it was already. */
  private boolean recordProcessed(UUID eventId) {
    return jdbc.sql(
                """
                insert into processed_event (event_id)
                values (:eventId)
                on conflict (event_id) do nothing
                """)
            .param("eventId", eventId)
            .update()
        == 1;
  }

  private void insertStatementLine(TransactionPosted event, PostedEntry entry) {
    jdbc.sql(
            """
            insert into statement_line (
                account_id, entry_seq, transaction_id, booked_at, side, amount_pence,
                balance_after_pence, counterparty_account_id)
            values (
                :accountId, :entrySeq, :transactionId, :bookedAt, :side, :amountPence,
                :balanceAfterPence, :counterpartyAccountId)
            """)
        .param("accountId", entry.accountId())
        .param("entrySeq", entry.entrySeq())
        .param("transactionId", event.payload().transactionId())
        .param("bookedAt", event.payload().bookingTime().atOffset(ZoneOffset.UTC))
        .param("side", entry.side())
        .param("amountPence", entry.amountPence())
        .param("balanceAfterPence", entry.balanceAfterPence())
        .param("counterpartyAccountId", event.counterpartyOf(entry))
        .update();
  }

  /**
   * Moves the Account's Balance to the Entry's balance_after only if the Entry comes later in the
   * Account's sequence than the last one applied, so events may arrive in any order.
   */
  private void updateBalance(PostedEntry entry) {
    jdbc.sql(
            """
            insert into account_balance_view (account_id, balance_pence, last_entry_seq)
            values (:accountId, :balanceAfterPence, :entrySeq)
            on conflict (account_id) do update
            set balance_pence = excluded.balance_pence,
                last_entry_seq = excluded.last_entry_seq,
                updated_at = clock_timestamp()
            where account_balance_view.last_entry_seq < excluded.last_entry_seq
            """)
        .param("accountId", entry.accountId())
        .param("balanceAfterPence", entry.balanceAfterPence())
        .param("entrySeq", entry.entrySeq())
        .update();
  }
}
