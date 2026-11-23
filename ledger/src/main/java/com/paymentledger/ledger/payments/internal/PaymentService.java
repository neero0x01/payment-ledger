package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.outbox.Outbox;
import com.paymentledger.ledger.payments.EndToEndId;
import com.paymentledger.ledger.payments.Payee;
import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.PaymentStatus;
import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.payments.SchemeOutcome;
import com.paymentledger.ledger.payments.SchemeReasonCode;
import com.paymentledger.ledger.payments.internal.PaymentRepository.InsertedPayment;
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
import com.paymentledger.ledger.shared.TransactionId;
import java.time.Instant;
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
  private final Outbox outbox;

  PaymentService(PaymentRepository payments, Posting posting, Accounts accounts, Outbox outbox) {
    this.payments = payments;
    this.posting = posting;
    this.accounts = accounts;
    this.outbox = outbox;
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
    requireAllowedAmount(amount);
    if (debtor.equals(payee)) {
      throw Problem.validationFailed("The Payee must be a different Account from the payer");
    }
    Account payer = payerOwnedBy(customer, debtor);
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
      Instant rejectedAt = payments.markRejected(id, RejectionReason.INSUFFICIENT_FUNDS);
      Payment rejected = payments.find(id).orElseThrow();
      outbox.append(PaymentStatusChangedEvent.of(rejected, rejectedAt));
      return IdempotentOutcome.first(rejected);
    }
    posting.post(
        NewTransaction.forPayment(
            id,
            List.of(new Entry(debtor, Side.DEBIT, amount), new Entry(payee, Side.CREDIT, amount))));
    Instant completedAt = payments.markCompleted(id);
    Payment completed = payments.find(id).orElseThrow();
    outbox.append(PaymentStatusChangedEvent.of(completed, completedAt));
    return IdempotentOutcome.first(completed);
  }

  /**
   * Checks the Customer owns the payer's Account, inserts the Pending Faster Payment with a new
   * End-to-End Id and posts its Transaction, which moves the money into FPS Clearing. All in one
   * database transaction. The Payment stays Pending until its Scheme Outcome; the Scheme Submission
   * is sent only after commit, by the dispatcher. A Payment the Customer already made with the
   * Idempotency-Key is replayed as it is now, and nothing new is posted.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  IdempotentOutcome<Payment> makeFasterPayment(
      CustomerId customer,
      IdempotencyKey key,
      AccountId debtor,
      Payee.AtAnotherBank payee,
      Money amount) {
    requireAllowedAmount(amount);
    payerOwnedBy(customer, debtor);
    KeyedPayment keyed =
        new KeyedPayment(
            customer,
            key,
            RequestHash.of(
                "FASTER_PAYMENT",
                debtor.value().toString(),
                payee.sortCode(),
                payee.accountNumber(),
                payee.name(),
                Long.toString(amount.pence())));
    Optional<InsertedPayment> inserted =
        payments.insertPendingFasterPayment(keyed, debtor, payee, amount, EndToEndId.random());
    if (inserted.isEmpty()) {
      return replay(payments.findByIdempotencyKey(keyed).orElseThrow(), keyed.requestHash());
    }
    PaymentId id = inserted.get().id();
    AccountId fpsClearing = accounts.fpsClearing();
    posting.lockBalances(Set.of(debtor, fpsClearing));
    posting.post(
        NewTransaction.forPayment(
            id,
            List.of(
                new Entry(debtor, Side.DEBIT, amount),
                new Entry(fpsClearing, Side.CREDIT, amount))));
    Payment pending = payments.find(id).orElseThrow();
    outbox.append(PaymentStatusChangedEvent.of(pending, inserted.get().createdAt()));
    return IdempotentOutcome.first(pending);
  }

  /**
   * Records the Scheme Outcome of a Pending Faster Payment: an accepted one makes it Completed with
   * no further Transaction, because the money stays in FPS Clearing; a Scheme Rejection makes it
   * Rejected and posts a Reversal. An outcome for a Payment that is no longer Pending changes
   * nothing. Refuses an unknown End-to-End Id as not-found.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  void recordSchemeOutcome(SchemeOutcome outcome) {
    Payment payment =
        payments
            .lockByEndToEndId(outcome.endToEndId())
            .orElseThrow(() ->
                Problem.notFound(
                    "No Faster Payment has End-to-End Id " + outcome.endToEndId().value()));
    if (payment.status() != PaymentStatus.PENDING) {
      return;
    }
    Instant changedAt =
        switch (outcome.status()) {
          case ACSC -> payments.markCompleted(payment.id());
          case RJCT -> rejectAndReverse(payment, outcome.rejection().orElseThrow());
        };
    outbox.append(
        PaymentStatusChangedEvent.of(payments.find(payment.id()).orElseThrow(), changedAt));
  }

  /**
   * Rejects the Faster Payment for the Scheme Rejection and posts the Reversal of its Transaction,
   * which returns the money from FPS Clearing to the payer. Returns when it was Rejected.
   */
  private Instant rejectAndReverse(Payment payment, SchemeReasonCode reasonCode) {
    Instant rejectedAt = payments.markSchemeRejected(payment.id(), reasonCode);
    TransactionId original = posting.transactionsOf(payment.id()).getFirst();
    posting.post(NewTransaction.reversal(posting.find(original).orElseThrow()));
    return rejectedAt;
  }

  private static void requireAllowedAmount(Money amount) {
    Problem.requirePositive(amount);
    if (amount.isGreaterThan(MAXIMUM_AMOUNT)) {
      throw Problem.validationFailed("The amount must be at most £1,000,000.00");
    }
  }

  /** The payer's Account, refused unless it exists and the Customer owns it. */
  private Account payerOwnedBy(CustomerId customer, AccountId debtor) {
    Account payer =
        accounts
            .find(debtor)
            .orElseThrow(
                () -> Problem.notFound("No Account " + debtor.value() + " exists to pay from"));
    if (!payer.isOwnedBy(customer)) {
      throw Problem.forbidden("Account " + debtor.value() + " is not yours to pay from");
    }
    return payer;
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
