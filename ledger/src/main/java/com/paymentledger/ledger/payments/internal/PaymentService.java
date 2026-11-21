package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.Side;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentService {

  static final Money MAXIMUM_AMOUNT = Money.ofPence(1_000_000_00);

  private final PaymentRepository payments;
  private final Posting posting;
  private final Accounts accounts;

  PaymentService(PaymentRepository payments, Posting posting, Accounts accounts) {
    this.payments = payments;
    this.posting = posting;
    this.accounts = accounts;
  }

  /**
   * Inserts the Payment, then either posts its Transaction and completes it or, when the payer's
   * funds don't cover it, Rejects it with Insufficient Funds. All in one database transaction.
   */
  @Transactional
  Payment makeInternalTransfer(AccountId debtor, AccountId payee, Money amount) {
    if (!amount.isPositive()) {
      throw PaymentProblems.validationFailed("The amount must be more than zero");
    }
    if (amount.isGreaterThan(MAXIMUM_AMOUNT)) {
      throw PaymentProblems.validationFailed("The amount must be at most £1,000,000.00");
    }
    if (debtor.equals(payee)) {
      throw PaymentProblems.validationFailed(
          "The Payee must be a different Account from the payer");
    }
    Account payer =
        accounts
            .find(debtor)
            .orElseThrow(() ->
                PaymentProblems.notFound("No Account " + debtor.value() + " exists to pay from"));
    if (accounts.find(payee).isEmpty()) {
      throw PaymentProblems.notFound("No Account " + payee.value() + " exists to pay");
    }
    PaymentId id = payments.insertPendingInternalTransfer(debtor, payee, amount);
    Money balance = posting.balanceOf(debtor).orElseThrow();
    if (!payer.canPayOut(amount, balance)) {
      payments.markRejected(id, RejectionReason.INSUFFICIENT_FUNDS);
      return payments.find(id).orElseThrow();
    }
    posting.post(
        NewTransaction.forPayment(
            id,
            List.of(new Entry(debtor, Side.DEBIT, amount), new Entry(payee, Side.CREDIT, amount))));
    payments.markCompleted(id);
    return payments.find(id).orElseThrow();
  }

  @Transactional(readOnly = true)
  Optional<Payment> find(PaymentId id) {
    return payments.find(id);
  }
}
