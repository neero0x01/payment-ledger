package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.posting.CorrectionDetails;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.posting.internal.JdbcPosting.KeyedCorrection;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.IdempotencyKey;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.OperatorId;
import com.paymentledger.ledger.shared.Problem;
import com.paymentledger.ledger.shared.Side;
import com.paymentledger.ledger.shared.TransactionId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

  private final JdbcPosting posting;
  private final Accounts accounts;

  CorrectionService(JdbcPosting posting, Accounts accounts) {
    this.posting = posting;
    this.accounts = accounts;
  }

  /** The Correction a request posted, or the one it replayed because its key was used. */
  record Posted(PostedTransaction correction, boolean replayed) {}

  /**
   * Posts an Adjustment of the amount to the Customer Account on the given side, against the
   * Corrections Account. Corrections skip the funds check.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  Posted adjust(
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
    Optional<Posted> replay = replay(keyed);
    if (replay.isPresent()) {
      return replay.get();
    }
    return postOnce(
        NewTransaction.adjustment(
            customerAccount, side, amount, accounts.corrections(), correction),
        keyed);
  }

  /**
   * Posts a Reversing Correction of the original Transaction, booked now. A Transaction is reversed
   * at most once; the unique index on the link settles two reversals that race.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  Posted reverse(
      OperatorId operator,
      IdempotencyKey key,
      TransactionId original,
      CorrectionDetails correction) {
    PostedTransaction mistake =
        posting
            .find(original)
            .orElseThrow(() -> Problem.notFound("No Transaction " + original.value() + " exists"));
    KeyedCorrection keyed =
        new KeyedCorrection(
            operator, key, requestHash(correction, "REVERSING", original.value().toString()));
    Optional<Posted> replay = replay(keyed);
    if (replay.isPresent()) {
      return replay.get();
    }
    if (posting.isReversed(original)) {
      throw alreadyReversed(original);
    }
    try {
      return postOnce(NewTransaction.reversingCorrection(mistake, correction), keyed);
    } catch (DuplicateKeyException raced) {
      throw alreadyReversed(original);
    }
  }

  /** Posts the Correction, or replays the one a request with the same key posted meanwhile. */
  private Posted postOnce(NewTransaction correction, KeyedCorrection keyed) {
    Optional<TransactionId> posted = posting.postOnce(correction, keyed);
    if (posted.isEmpty()) {
      return replay(keyed).orElseThrow();
    }
    return new Posted(posting.find(posted.get()).orElseThrow(), false);
  }

  /** The Correction the Operator already posted with the key, unless a different request did. */
  private Optional<Posted> replay(KeyedCorrection keyed) {
    return posting
        .findByIdempotencyKey(keyed.operator(), keyed.key())
        .map(found -> {
          if (!found.requestHash().equals(keyed.requestHash())) {
            throw Problem.idempotencyKeyReused(
                "This Idempotency-Key was already used for a different Correction request");
          }
          return new Posted(posting.find(found.id()).orElseThrow(), true);
        });
  }

  private static RuntimeException alreadyReversed(TransactionId original) {
    return Problem.alreadyReversed(
        "Transaction " + original.value() + " has already been reversed");
  }

  /** The SHA-256 of the canonical Correction request, as hex; the free-text note comes last. */
  private static String requestHash(CorrectionDetails correction, String... fields) {
    String canonical =
        String.join("\n", fields) + "\n" + correction.reason().name() + "\n" + correction.note();
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java platform has SHA-256", e);
    }
  }
}
