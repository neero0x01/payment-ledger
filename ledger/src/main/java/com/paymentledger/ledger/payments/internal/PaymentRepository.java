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
import java.sql.ResultSet;
import java.sql.SQLException;
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

  /**
   * Inserts the Pending Payment, or nothing when the principal already made one with the key. A
   * retry still in flight blocks on the unique index until the first commits or rolls back.
   */
  Optional<PaymentId> insertPendingInternalTransfer(
      CustomerId principal,
      IdempotencyKey key,
      String requestHash,
      AccountId debtor,
      AccountId payee,
      Money amount) {
    return jdbc.sql(
            """
            insert into payment (principal_id, idempotency_key, request_hash, type,
                                 debtor_account_id, payee_account_id, amount_pence, status)
            values (:principal, :key, :requestHash, 'INTERNAL_TRANSFER',
                    :debtor, :payee, :amountPence, 'PENDING')
            on conflict (principal_id, idempotency_key) do nothing
            returning id
            """)
        .param("principal", principal.value())
        .param("key", key.value())
        .param("requestHash", requestHash)
        .param("debtor", debtor.value())
        .param("payee", payee.value())
        .param("amountPence", amount.pence())
        .query(UUID.class)
        .optional()
        .map(PaymentId::new);
  }

  void markCompleted(PaymentId id) {
    jdbc.sql(
            """
            update payment
            set status = 'COMPLETED', completed_at = clock_timestamp()
            where id = :id
            """)
        .param("id", id.value())
        .update();
  }

  void markRejected(PaymentId id, RejectionReason reason) {
    jdbc.sql(
            """
            update payment
            set status = 'REJECTED', rejection_reason = :reason
            where id = :id
            """)
        .param("id", id.value())
        .param("reason", reason.name())
        .update();
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

  /** A Payment made with an Idempotency-Key, and the hash of the request that made it. */
  record KeyedPayment(PaymentId id, String requestHash) {}

  /** The Payment the principal made with this Idempotency-Key, if any. */
  Optional<KeyedPayment> findByIdempotencyKey(CustomerId principal, IdempotencyKey key) {
    return jdbc.sql(
            """
            select id, request_hash
            from payment
            where principal_id = :principal and idempotency_key = :key
            """)
        .param("principal", principal.value())
        .param("key", key.value())
        .query((rs, rowNum) ->
            new KeyedPayment(
                new PaymentId(rs.getObject("id", UUID.class)), rs.getString("request_hash")))
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
