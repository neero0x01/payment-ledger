package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.EndToEndId;
import com.paymentledger.ledger.payments.Payee;
import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.PaymentStatus;
import com.paymentledger.ledger.payments.PaymentType;
import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.payments.SchemeSubmission;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.IdempotencyKey;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.RequestHash;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PaymentRepository {

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
   * Claims up to the limit of Pending Faster Payments that have never been submitted, oldest first,
   * counting a Scheme Submission for each, and returns what to submit. Rows another dispatcher has
   * locked are skipped.
   */
  List<SchemeSubmission> claimUnsubmittedFasterPayments(int limit) {
    return jdbc.sql(
            """
            update payment
            set submission_attempts = submission_attempts + 1,
                last_submitted_at = clock_timestamp()
            where id in (
                select id
                from payment
                where type = 'FASTER_PAYMENT' and status = 'PENDING' and submission_attempts = 0
                order by created_at
                limit :limit
                for update skip locked)
            returning end_to_end_id, debtor_account_id, payee_sort_code, payee_account_number,
                      payee_name, amount_pence
            """)
        .param("limit", limit)
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

  /** Marks the Payment Completed and returns when it was. */
  Instant markCompleted(PaymentId id) {
    return jdbc.sql(
            """
            update payment
            set status = 'COMPLETED', completed_at = clock_timestamp()
            where id = :id
            returning completed_at
            """)
        .param("id", id.value())
        .query((rs, rowNum) -> rs.getObject("completed_at", OffsetDateTime.class).toInstant())
        .single();
  }

  /** Marks the Payment Rejected for the reason given and returns when it was. */
  Instant markRejected(PaymentId id, RejectionReason reason) {
    return jdbc.sql(
            """
            update payment
            set status = 'REJECTED', rejection_reason = :reason
            where id = :id
            returning clock_timestamp() as rejected_at
            """)
        .param("id", id.value())
        .param("reason", reason.name())
        .query((rs, rowNum) -> rs.getObject("rejected_at", OffsetDateTime.class).toInstant())
        .single();
  }

  Optional<Payment> find(PaymentId id) {
    return jdbc.sql(
            """
            select id, type, status, debtor_account_id, payee_account_id, payee_sort_code,
                   payee_account_number, payee_name, amount_pence, rejection_reason
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
                   payee_account_number, payee_name, amount_pence, rejection_reason
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
        rejectionReason == null ? null : RejectionReason.valueOf(rejectionReason));
  }
}
