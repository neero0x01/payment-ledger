package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.PaymentStatus;
import com.paymentledger.ledger.payments.PaymentType;
import com.paymentledger.ledger.shared.AccountId;
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

  PaymentId insertPendingInternalTransfer(AccountId debtor, AccountId payee, Money amount) {
    UUID id =
        jdbc.sql(
                """
                insert into payment (type, debtor_account_id, payee_account_id, amount_pence, status)
                values ('INTERNAL_TRANSFER', :debtor, :payee, :amountPence, 'PENDING')
                returning id
                """)
            .param("debtor", debtor.value())
            .param("payee", payee.value())
            .param("amountPence", amount.pence())
            .query(UUID.class)
            .single();
    return new PaymentId(id);
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

  Optional<Payment> find(PaymentId id) {
    return jdbc.sql(
            """
            select id, type, status, debtor_account_id, payee_account_id, amount_pence
            from payment
            where id = :id
            """)
        .param("id", id.value())
        .query(PaymentRepository::toPayment)
        .optional();
  }

  private static Payment toPayment(ResultSet rs, int rowNum) throws SQLException {
    return new Payment(
        new PaymentId(rs.getObject("id", UUID.class)),
        PaymentType.valueOf(rs.getString("type")),
        PaymentStatus.valueOf(rs.getString("status")),
        new AccountId(rs.getObject("debtor_account_id", UUID.class)),
        new AccountId(rs.getObject("payee_account_id", UUID.class)),
        Money.ofPence(rs.getLong("amount_pence")));
  }
}
