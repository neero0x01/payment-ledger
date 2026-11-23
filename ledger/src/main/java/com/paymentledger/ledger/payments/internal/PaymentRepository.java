package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.PaymentStatus;
import com.paymentledger.ledger.payments.PaymentType;
import com.paymentledger.ledger.payments.RejectionReason;
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
            select id, type, status, debtor_account_id, payee_account_id, amount_pence,
                   rejection_reason
            from payment
            where id = :id
            """)
        .param("id", id.value())
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
    return new Payment(
        new PaymentId(rs.getObject("id", UUID.class)),
        PaymentType.valueOf(rs.getString("type")),
        PaymentStatus.valueOf(rs.getString("status")),
        new AccountId(rs.getObject("debtor_account_id", UUID.class)),
        new AccountId(rs.getObject("payee_account_id", UUID.class)),
        Money.ofPence(rs.getLong("amount_pence")),
        rejectionReason == null ? null : RejectionReason.valueOf(rejectionReason));
  }
}
