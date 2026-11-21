package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.Side;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentService {

  private final PaymentRepository payments;
  private final Posting posting;

  PaymentService(PaymentRepository payments, Posting posting) {
    this.payments = payments;
    this.posting = posting;
  }

  /**
   * Inserts the Payment, posts its Transaction and completes it, all in one database transaction.
   */
  @Transactional
  Payment makeInternalTransfer(AccountId debtor, AccountId payee, Money amount) {
    PaymentId id = payments.insertPendingInternalTransfer(debtor, payee, amount);
    posting.post(
        NewTransaction.forPayment(
            id,
            List.of(new Entry(debtor, Side.DEBIT, amount), new Entry(payee, Side.CREDIT, amount))));
    payments.markCompleted(id);
    return payments.find(id).orElseThrow();
  }
}
