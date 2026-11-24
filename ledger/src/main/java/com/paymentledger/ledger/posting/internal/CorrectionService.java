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
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts Corrections: new Transactions that fix mistakes without changing the originals. A
 * Correction the Operator already posted with the Idempotency-Key is replayed, and nothing new is
 * posted (ADR 0007).
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
   * Corrections Account. Corrections skip the funds check.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  IdempotentOutcome<PostedTransaction> adjust(
      OperatorId operator,
      IdempotencyKey key,
      AccountId customerAccount,
      Side side,
      Money amount,
      CorrectionDetails correction) {
    Account account =
        accounts
            .find(customerAccount)
            .orElseThrow(
                () -> Problem.notFound("No Account " + customerAccount.value() + " exists"));
    if (!account.isCustomerAccount()) {
      throw Problem.validationFailed(
          "An Adjustment moves money between a Customer Account and the Corrections Account");
    }
    if (logins.isDemoOperator(operator) && !accounts.isInDemoSandbox(customerAccount)) {
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
                Long.toString(amount.pence())));
    Optional<IdempotentOutcome<PostedTransaction>> replay = replay(keyed);
    if (replay.isPresent()) {
      return replay.get();
    }
    return posting
        .postCorrection(
            NewTransaction.adjustment(
                customerAccount, side, amount, accounts.corrections(), correction),
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
    PostedTransaction mistake =
        posting
            .find(original)
            .orElseThrow(() -> Problem.notFound("No Transaction " + original.value() + " exists"));
    if (logins.isDemoOperator(operator) && !isOnDemoSandboxAccountsOnly(mistake)) {
      throw outsideDemoSandboxes();
    }
    KeyedCorrection keyed =
        new KeyedCorrection(
            operator, key, requestHash(correction, "REVERSING", original.value().toString()));
    Optional<IdempotentOutcome<PostedTransaction>> replay = replay(keyed);
    if (replay.isPresent()) {
      return replay.get();
    }
    if (mistake.payment().filter(pendingPayments::isPending).isPresent()) {
      throw Problem.paymentPending(
          "Transaction " + original.value() + " belongs to a Payment that is still Pending");
    }
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

  private PostedTransaction find(TransactionId posted) {
    return posting.find(posted).orElseThrow();
  }

  /**
   * Whether the Transaction touches at least one Demo Sandbox Account and no Customer Account
   * outside the Demo Sandboxes. Internal Accounts, such as Funding, don't count.
   */
  private boolean isOnDemoSandboxAccountsOnly(PostedTransaction transaction) {
    List<AccountId> customerAccounts =
        transaction.entries().stream()
            .map(Entry::account)
            .distinct()
            .filter(account -> accounts.find(account).map(Account::isCustomerAccount).orElse(false))
            .toList();
    return !customerAccounts.isEmpty()
        && customerAccounts.stream().allMatch(accounts::isInDemoSandbox);
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
