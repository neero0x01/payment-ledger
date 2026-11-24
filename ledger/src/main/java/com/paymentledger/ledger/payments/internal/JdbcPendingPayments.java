package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.PaymentStatus;
import com.paymentledger.ledger.posting.PendingPayments;
import com.paymentledger.ledger.shared.PaymentId;
import org.springframework.stereotype.Component;

@Component
class JdbcPendingPayments implements PendingPayments {

  private final PaymentRepository payments;

  JdbcPendingPayments(PaymentRepository payments) {
    this.payments = payments;
  }

  @Override
  public boolean isPending(PaymentId payment) {
    return payments.find(payment).map(p -> p.status() == PaymentStatus.PENDING).orElse(false);
  }
}
