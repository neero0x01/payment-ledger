package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.IdempotencyKey;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.Principal;
import com.paymentledger.ledger.shared.Problem;
import com.paymentledger.ledger.shared.Side;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

  /** The Payment a request made, or the one it replayed because its Idempotency-Key was used. */
  record Made(Payment payment, boolean replayed) {}

  /**
   * Checks the Customer owns the payer's Account, inserts the Payment, then either posts its
   * Transaction and completes it or, when the payer's funds don't cover it, Rejects it with
   * Insufficient Funds. All in one database transaction. A Payment the Customer already made with
   * the Idempotency-Key is replayed as it is now, and nothing new is posted.
   */
  @Transactional
  Made makeInternalTransfer(
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
    Optional<PaymentId> made = payments.findByIdempotencyKey(customer, key);
    if (made.isPresent()) {
      return new Made(payments.find(made.get()).orElseThrow(), true);
    }
    PaymentId id =
        payments.insertPendingInternalTransfer(
            customer, key, requestHash(debtor, payee, amount), debtor, payee, amount);
    Money balance = posting.balanceOf(debtor).orElseThrow();
    if (!payer.canPayOut(amount, balance)) {
      payments.markRejected(id, RejectionReason.INSUFFICIENT_FUNDS);
      return new Made(payments.find(id).orElseThrow(), false);
    }
    posting.post(
        NewTransaction.forPayment(
            id,
            List.of(new Entry(debtor, Side.DEBIT, amount), new Entry(payee, Side.CREDIT, amount))));
    payments.markCompleted(id);
    return new Made(payments.find(id).orElseThrow(), false);
  }

  /** The SHA-256 of the canonical Internal Transfer request, as hex. */
  private static String requestHash(AccountId debtor, AccountId payee, Money amount) {
    String canonical =
        String.join(
            "\n",
            "INTERNAL_TRANSFER",
            debtor.value().toString(),
            payee.value().toString(),
            Long.toString(amount.pence()));
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java platform has SHA-256", e);
    }
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
