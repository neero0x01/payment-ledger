package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.payments.internal.PaymentRepository.KeyedPayment;
import com.paymentledger.ledger.payments.internal.PaymentRepository.PaymentMadeWithKey;
import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.IdempotencyKey;
import com.paymentledger.ledger.shared.IdempotentOutcome;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.Principal;
import com.paymentledger.ledger.shared.Problem;
import com.paymentledger.ledger.shared.RequestHash;
import com.paymentledger.ledger.shared.Side;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
   * Checks the Customer owns the payer's Account, inserts the Payment, then either posts its
   * Transaction and completes it or, when the payer's funds don't cover it, Rejects it with
   * Insufficient Funds. All in one database transaction, with the funds check run against the
   * payer's locked Balance so that concurrent Payments can never overdraw it. A Payment the
   * Customer already made with the Idempotency-Key is replayed as it is now, and nothing new is
   * posted.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  IdempotentOutcome<Payment> makeInternalTransfer(
      CustomerId customer, IdempotencyKey key, AccountId debtor, AccountId payee, Money amount) {
    Problem.requirePositive(amount);
    if (amount.isGreaterThan(MAXIMUM_AMOUNT)) {
      throw Problem.validationFailed("The amount must be at most £1,000,000.00");
    }
    if (debtor.equals(payee)) {
      throw Problem.validationFailed("The Payee must be a different Account from the payer");
    }
    Account payer =
        accounts
            .find(debtor)
            .orElseThrow(
                () -> Problem.notFound("No Account " + debtor.value() + " exists to pay from"));
    if (!payer.isOwnedBy(customer)) {
      throw Problem.forbidden("Account " + debtor.value() + " is not yours to pay from");
    }
    if (accounts.find(payee).isEmpty()) {
      throw Problem.notFound("No Account " + payee.value() + " exists to pay");
    }
    KeyedPayment keyed =
        new KeyedPayment(
            customer,
            key,
            RequestHash.of(
                "INTERNAL_TRANSFER",
                debtor.value().toString(),
                payee.value().toString(),
                Long.toString(amount.pence())));
    Optional<PaymentId> inserted =
        payments.insertPendingInternalTransfer(keyed, debtor, payee, amount);
    if (inserted.isEmpty()) {
      return replay(payments.findByIdempotencyKey(keyed).orElseThrow(), keyed.requestHash());
    }
    PaymentId id = inserted.get();
    Money balance = Objects.requireNonNull(posting.lockBalances(Set.of(debtor, payee)).get(debtor));
    if (!payer.canPayOut(amount, balance)) {
      payments.markRejected(id, RejectionReason.INSUFFICIENT_FUNDS);
      return IdempotentOutcome.first(payments.find(id).orElseThrow());
    }
    posting.post(
        NewTransaction.forPayment(
            id,
            List.of(new Entry(debtor, Side.DEBIT, amount), new Entry(payee, Side.CREDIT, amount))));
    payments.markCompleted(id);
    return IdempotentOutcome.first(payments.find(id).orElseThrow());
  }

  /** The Payment made with the key, unless it was made by a different request. */
  private IdempotentOutcome<Payment> replay(PaymentMadeWithKey made, RequestHash sent) {
    made.requestHash().requireSameRequest(sent, "Payment");
    return IdempotentOutcome.replay(payments.find(made.id()).orElseThrow());
  }

  /** The Payment, if the Principal may see it: an Operator, or the owner of the payer's Account. */
  @Transactional(readOnly = true)
  Optional<Payment> find(PaymentId id, Principal principal) {
    Optional<Payment> payment = payments.find(id);
    payment.ifPresent(found -> {
      boolean visible =
          accounts.find(found.debtorAccount()).map(a -> a.isVisibleTo(principal)).orElse(false);
      if (!visible) {
        throw Problem.forbidden("Payment " + id.value() + " is not yours to see");
      }
    });
    return payment;
  }
}
