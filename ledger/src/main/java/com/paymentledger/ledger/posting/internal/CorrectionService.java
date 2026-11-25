package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.accounts.Logins;
import com.paymentledger.ledger.posting.CorrectionDetails;
import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.KeyedCorrection;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.PendingPayments;
import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.posting.TransactionSource;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.IdempotencyKey;
import com.paymentledger.ledger.shared.IdempotentOutcome;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.OperatorId;
import com.paymentledger.ledger.shared.Problem;
import com.paymentledger.ledger.shared.RequestHash;
import com.paymentledger.ledger.shared.Side;
import com.paymentledger.ledger.shared.TransactionId;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts Corrections: new Transactions that fix mistakes without changing the originals. A
 * Correction the Operator already posted with the Idempotency-Key is replayed, and nothing new is
 * posted (ADR 0007). Seeded data is read-only for every Operator (spec §8): a Correction of a
 * source=SEED Transaction, or one that would touch only seeded Customer Accounts, is refused as
 * seed-data-read-only. The seed posts its own Corrections directly, never through here.
 */
@Service
class CorrectionService {

  private final Posting posting;
  private final Accounts accounts;
  private final PendingPayments pendingPayments;
  private final Logins logins;

  CorrectionService(
      Posting posting, Accounts accounts, PendingPayments pendingPayments, Logins logins) {
    this.posting = posting;
    this.accounts = accounts;
    this.pendingPayments = pendingPayments;
    this.logins = logins;
  }

  /**
   * Posts an Adjustment of the amount to the Customer Account on the given side, against the
   * Corrections Account, linked to the Transaction it corrects if it names one. Corrections skip
   * the funds check. Refuses to correct a Transaction whose Payment is still Pending as
   * payment-pending.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  IdempotentOutcome<PostedTransaction> adjust(
      OperatorId operator,
      IdempotencyKey key,
      AccountId customerAccount,
      Side side,
      Money amount,
      CorrectionDetails correction,
      Optional<TransactionId> corrects) {
    Optional<PostedTransaction> mistake = corrects.map(this::existing);
    Account account =
        accounts
            .find(customerAccount)
            .orElseThrow(
                () -> Problem.notFound("No Account " + customerAccount.value() + " exists"));
    if (!account.isCustomerAccount()) {
      throw Problem.validationFailed(
          "An Adjustment moves money between a Customer Account and the Corrections Account");
    }
    if (accounts.areSeededOnly(Set.of(customerAccount))
        || mistake.filter(CorrectionService::isSeeded).isPresent()) {
      throw seedDataReadOnly();
    }
    if (logins.isDemoOperator(operator)
        && !accounts.areInDemoSandboxesOnly(Set.of(customerAccount))) {
      throw outsideDemoSandboxes();
    }
    KeyedCorrection keyed =
        new KeyedCorrection(
            operator,
            key,
            requestHash(
                correction,
                "ADJUSTMENT",
                customerAccount.value().toString(),
                side.name(),
                Long.toString(amount.pence()),
                corrects.map(id -> id.value().toString()).orElse("")));
    Optional<IdempotentOutcome<PostedTransaction>> replay = replay(keyed);
    if (replay.isPresent()) {
      return replay.get();
    }
    mistake.ifPresent(this::refuseWhilePaymentPending);
    return posting
        .postCorrection(
            NewTransaction.adjustment(
                customerAccount, side, amount, accounts.corrections(), correction, corrects),
            keyed)
        .map(this::find);
  }

  /**
   * Posts a Reversing Correction of the original Transaction, booked now. A Transaction is reversed
   * at most once; the unique index on the link settles two reversals that race. Refuses a
   * Transaction whose Payment is still Pending as payment-pending: its Scheme Outcome may yet post
   * a Reversal.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  IdempotentOutcome<PostedTransaction> reverse(
      OperatorId operator,
      IdempotencyKey key,
      TransactionId original,
      CorrectionDetails correction) {
    PostedTransaction mistake = existing(original);
    if (isSeeded(mistake) || accounts.areSeededOnly(accountsOf(mistake))) {
      throw seedDataReadOnly();
    }
    if (logins.isDemoOperator(operator) && !accounts.areInDemoSandboxesOnly(accountsOf(mistake))) {
      throw outsideDemoSandboxes();
    }
    KeyedCorrection keyed =
        new KeyedCorrection(
            operator, key, requestHash(correction, "REVERSING", original.value().toString()));
    Optional<IdempotentOutcome<PostedTransaction>> replay = replay(keyed);
    if (replay.isPresent()) {
      return replay.get();
    }
    refuseWhilePaymentPending(mistake);
    if (posting.isReversed(original)) {
      throw alreadyReversed(original);
    }
    try {
      return posting
          .postCorrection(NewTransaction.reversingCorrection(mistake, correction), keyed)
          .map(this::find);
    } catch (DuplicateKeyException raced) {
      throw alreadyReversed(original);
    }
  }

  /** The Correction the Operator already posted with the key, unless a different request did. */
  private Optional<IdempotentOutcome<PostedTransaction>> replay(KeyedCorrection keyed) {
    return posting.correctionPostedWith(keyed).map(id -> IdempotentOutcome.replay(find(id)));
  }

  private PostedTransaction existing(TransactionId transaction) {
    return posting
        .find(transaction)
        .orElseThrow(() -> Problem.notFound("No Transaction " + transaction.value() + " exists"));
  }

  /** A late Scheme Outcome could post a Reversal of it, refunding the Customer twice. */
  private void refuseWhilePaymentPending(PostedTransaction mistake) {
    if (mistake.payment().filter(pendingPayments::isPending).isPresent()) {
      throw Problem.paymentPending(
          "Transaction " + mistake.id().value() + " belongs to a Payment that is still Pending");
    }
  }

  private PostedTransaction find(TransactionId posted) {
    return posting.find(posted).orElseThrow();
  }

  private static boolean isSeeded(PostedTransaction transaction) {
    return transaction.source() == TransactionSource.SEED;
  }

  private static Set<AccountId> accountsOf(PostedTransaction transaction) {
    return transaction.entries().stream().map(Entry::account).collect(Collectors.toSet());
  }

  private static RuntimeException seedDataReadOnly() {
    return Problem.seedDataReadOnly("Seeded data is read-only: no Correction may touch it");
  }

  private static RuntimeException outsideDemoSandboxes() {
    return Problem.forbidden(
        "The demo Operator corrects only Transactions on Demo Sandbox Accounts");
  }

  private static RuntimeException alreadyReversed(TransactionId original) {
    return Problem.alreadyReversed(
        "Transaction " + original.value() + " has already been reversed");
  }

  /** The hash of the canonical Correction request: its fields, then the free-text note last. */
  private static RequestHash requestHash(CorrectionDetails correction, String... fields) {
    String[] canonical = Arrays.copyOf(fields, fields.length + 2);
    canonical[fields.length] = correction.reason().name();
    canonical[fields.length + 1] = correction.note();
    return RequestHash.of(canonical);
  }
}
