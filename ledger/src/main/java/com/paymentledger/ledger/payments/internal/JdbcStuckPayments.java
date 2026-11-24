package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.StuckPayment;
import com.paymentledger.ledger.payments.StuckPayments;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
class JdbcStuckPayments implements StuckPayments {

  private final PaymentRepository payments;

  JdbcStuckPayments(PaymentRepository payments) {
    this.payments = payments;
  }

  @Override
  public List<StuckPayment> oldest(int limit) {
    return payments.oldestStuckPayments(limit);
  }
}
