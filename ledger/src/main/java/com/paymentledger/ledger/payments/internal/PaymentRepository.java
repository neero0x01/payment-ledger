package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.EndToEndId;
import com.paymentledger.ledger.payments.Payee;
import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.PaymentStatus;
import com.paymentledger.ledger.payments.PaymentType;
import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.payments.SchemeReasonCode;
import com.paymentledger.ledger.payments.SchemeSubmission;
import com.paymentledger.ledger.payments.StuckPayment;
import com.paymentledger.ledger.payments.StuckPayments;
import com.paymentledger.ledger.posting.PendingPayments;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.IdempotencyKey;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.RequestHash;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The Payments, by hand-written SQL (ADR 0001). It is also the payments module's adapter for the
 * StuckPayments it shows Operators and the PendingPayments port that posting asks.
 */
@Repository
class PaymentRepository implements StuckPayments, PendingPayments {

  private final JdbcClient jdbc;

  PaymentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** A Payment request's Idempotency-Key, the Customer who sent it and the hash of the request. */
  record KeyedPayment(CustomerId customer, IdempotencyKey key, RequestHash requestHash) {}

  /**
   * The Payment a Customer made with an Idempotency-Key, and the hash of the request that made it.
   */
  record PaymentMadeWithKey(PaymentId id, RequestHash requestHash) {}

  /**
   * Inserts the Pending Payment, or nothing when the Customer already made one with the key. A
   * retry still in flight blocks on the unique index until the first commits or rolls back.
   */
  Optional<PaymentId> insertPendingInternalTransfer(
      KeyedPayment keyed, AccountId debtor, AccountId payee, Money amount) {
    return jdbc.sql(
            """
            insert into payment (principal_id, idempotency_key, request_hash, type,
                                 debtor_account_id, payee_account_id, amount_pence, status)
            values (:principal, :key, :requestHash, 'INTERNAL_TRANSFER',
                    :debtor, :payee, :amountPence, 'PENDING')
            on conflict (principal_id, idempotency_key) do nothing
            returning id
            """)
        .param("principal", keyed.customer().value())
        .param("key", keyed.key().value())
        .param("requestHash", keyed.requestHash().value())
        .param("debtor", debtor.value())
        .param("payee", payee.value())
        .param("amountPence", amount.pence())
        .query(UUID.class)
        .optional()
        .map(PaymentId::new);
  }

  /** A Payment just inserted, and when it was. */
  record InsertedPayment(PaymentId id, Instant createdAt) {}

  /**
   * Inserts the Pending Faster Payment with its End-to-End Id, or nothing when the Customer already
   * made one with the key. A retry still in flight blocks on the unique index until the first
   * commits or rolls back.
   */
  Optional<InsertedPayment> insertPendingFasterPayment(
      KeyedPayment keyed,
      AccountId debtor,
      Payee.AtAnotherBank payee,
      Money amount,
      EndToEndId endToEndId) {
    return jdbc.sql(
            """
            insert into payment (principal_id, idempotency_key, request_hash, type,
                                 debtor_account_id, payee_sort_code, payee_account_number,
                                 payee_name, amount_pence, status, end_to_end_id)
            values (:principal, :key, :requestHash, 'FASTER_PAYMENT',
                    :debtor, :sortCode, :accountNumber,
                    :name, :amountPence, 'PENDING', :endToEndId)
            on conflict (principal_id, idempotency_key) do nothing
            returning id, created_at
            """)
        .param("principal", keyed.customer().value())
        .param("key", keyed.key().value())
        .param("requestHash", keyed.requestHash().value())
        .param("debtor", debtor.value())
        .param("sortCode", payee.sortCode())
        .param("accountNumber", payee.accountNumber())
        .param("name", payee.name())
        .param("amountPence", amount.pence())
        .param("endToEndId", endToEndId.value())
        .query((rs, rowNum) ->
            new InsertedPayment(
                new PaymentId(rs.getObject("id", UUID.class)),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()))
        .optional();
  }

  /**
   * Claims up to the limit of Pending Faster Payments due a Scheme Submission, oldest first,
   * counting a Scheme Submission for each, and returns what to submit. A Payment is due when it has
   * never been submitted, or when its last attempt is older than its delay: the retry delay when
   * the last Scheme Submission failed, the resend delay when it was sent but no Scheme Outcome
   * came, and the longer stuck resend delay once it is a Stuck Payment. A Stuck Payment is still
   * sent, with the same End-to-End Id, so it completes once the scheme answers. Rows another
   * dispatcher has locked are skipped.
   */
  List<SchemeSubmission> claimFasterPaymentsToSubmit(
      int limit, Duration retryDelay, Duration resendAfter, Duration stuckResendAfter) {
    return jdbc.sql(
            """
            update payment
            set submission_attempts = submission_attempts + 1,
                last_submitted_at = clock_timestamp()
            where id in (
                select id
                from payment
                where type = 'FASTER_PAYMENT' and status = 'PENDING'
                  and (last_submitted_at is null
                       or last_submitted_at < clock_timestamp() - make_interval(secs =>
                              case
                                when stuck then :stuckResendAfter
                                when submission_attempts = 0 then :retryDelay
                                else :resendAfter
                              end))
                order by created_at
                limit :limit
                for update skip locked)
            returning end_to_end_id, debtor_account_id, payee_sort_code, payee_account_number,
                      payee_name, amount_pence
            """)
        .param("limit", limit)
        .param("retryDelay", retryDelay.toMillis() / 1000.0)
        .param("resendAfter", resendAfter.toMillis() / 1000.0)
        .param("stuckResendAfter", stuckResendAfter.toMillis() / 1000.0)
        .query((rs, rowNum) ->
            new SchemeSubmission(
                new EndToEndId(rs.getString("end_to_end_id")),
                new AccountId(rs.getObject("debtor_account_id", UUID.class)),
                new Payee.AtAnotherBank(
                    rs.getString("payee_sort_code"),
                    rs.getString("payee_account_number"),
                    rs.getString("payee_name")),
                Money.ofPence(rs.getLong("amount_pence"))))
        .list();
  }

  /**
   * Gives back the claim on a Pending Faster Payment whose Scheme Submission failed, so it no
   * longer counts and a later poll claims the Payment again.
   */
  void releaseFailedSubmission(EndToEndId endToEndId) {
    jdbc.sql(
            """
            update payment
            set submission_attempts = submission_attempts - 1
            where end_to_end_id = :endToEndId and status = 'PENDING' and submission_attempts > 0
            """)
        .param("endToEndId", endToEndId.value())
        .update();
  }

  /**
   * Flags as Stuck Payments the Pending Faster Payments made longer ago than the deadline, and
   * returns their End-to-End Ids. They stay Pending and are still sent: nothing is reversed.
   */
  List<EndToEndId> flagStuckPayments(Duration deadline) {
    return jdbc.sql(
            """
            update payment
            set stuck = true
            where type = 'FASTER_PAYMENT' and status = 'PENDING' and not stuck
              and created_at < clock_timestamp() - make_interval(secs => :deadline)
            returning end_to_end_id
            """)
        .param("deadline", deadline.toMillis() / 1000.0)
        .query((rs, rowNum) -> new EndToEndId(rs.getString("end_to_end_id")))
        .list();
  }

  @Override
  public long count() {
    return jdbc.sql("select count(*) from stuck_payment").query(Long.class).single();
  }

  @Override
  public List<StuckPayment> oldest(int limit) {
    return jdbc.sql(
            """
            select id, debtor_account_id, payee_sort_code, payee_account_number, payee_name,
                   amount_pence, created_at
            from stuck_payment
            order by created_at
            limit :limit
            """)
        .param("limit", limit)
        .query((rs, rowNum) ->
            new StuckPayment(
                new PaymentId(rs.getObject("id", UUID.class)),
                new AccountId(rs.getObject("debtor_account_id", UUID.class)),
                new Payee.AtAnotherBank(
                    rs.getString("payee_sort_code"),
                    rs.getString("payee_account_number"),
                    rs.getString("payee_name")),
                Money.ofPence(rs.getLong("amount_pence")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  @Override
  public boolean isPending(PaymentId payment) {
    return jdbc.sql("select status = 'PENDING' from payment where id = :id")
        .param("id", payment.value())
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }

  Optional<Payment> find(PaymentId id) {
    return jdbc.sql(
            """
            select id, type, status, debtor_account_id, payee_account_id, payee_sort_code,
                   payee_account_number, payee_name, amount_pence, rejection_reason,
                   scheme_reason_code, stuck
            from payment
            where id = :id
            """)
        .param("id", id.value())
        .query(PaymentRepository::toPayment)
        .optional();
  }

  /**
   * The Faster Payment with the End-to-End Id, locked until the database transaction ends, so two
   * Scheme Outcomes for it are recorded one after the other.
   */
  Optional<Payment> lockByEndToEndId(EndToEndId endToEndId) {
    return jdbc.sql(
            """
            select id, type, status, debtor_account_id, payee_account_id, payee_sort_code,
                   payee_account_number, payee_name, amount_pence, rejection_reason,
                   scheme_reason_code, stuck
            from payment
            where end_to_end_id = :endToEndId
            for update
            """)
        .param("endToEndId", endToEndId.value())
        .query(PaymentRepository::toPayment)
        .optional();
  }

  /** The Payment the Customer made with this Idempotency-Key, if any. */
  Optional<PaymentMadeWithKey> findByIdempotencyKey(KeyedPayment keyed) {
    return jdbc.sql(
            """
            select id, request_hash
            from payment
            where principal_id = :principal and idempotency_key = :key
            """)
        .param("principal", keyed.customer().value())
        .param("key", keyed.key().value())
        .query((rs, rowNum) ->
            new PaymentMadeWithKey(
                new PaymentId(rs.getObject("id", UUID.class)),
                new RequestHash(rs.getString("request_hash"))))
        .optional();
  }

  private static Payment toPayment(ResultSet rs, int rowNum) throws SQLException {
    String rejectionReason = rs.getString("rejection_reason");
    String schemeReasonCode = rs.getString("scheme_reason_code");
    PaymentType type = PaymentType.valueOf(rs.getString("type"));
    Payee payee =
        switch (type) {
          case INTERNAL_TRANSFER ->
              new Payee.AtTheFintech(new AccountId(rs.getObject("payee_account_id", UUID.class)));
          case FASTER_PAYMENT ->
              new Payee.AtAnotherBank(
                  rs.getString("payee_sort_code"),
                  rs.getString("payee_account_number"),
                  rs.getString("payee_name"));
        };
    return new Payment(
        new PaymentId(rs.getObject("id", UUID.class)),
        type,
        PaymentStatus.valueOf(rs.getString("status")),
        new AccountId(rs.getObject("debtor_account_id", UUID.class)),
        payee,
        Money.ofPence(rs.getLong("amount_pence")),
        rejectionReason == null ? null : RejectionReason.valueOf(rejectionReason),
        schemeReasonCode == null ? null : SchemeReasonCode.valueOf(schemeReasonCode),
        rs.getBoolean("stuck"));
  }
}
