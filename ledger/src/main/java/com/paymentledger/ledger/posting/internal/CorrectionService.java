package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.TransactionId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Posts Corrections: new Transactions that fix mistakes without changing the originals. */
@Service
class CorrectionService {

  private final Posting posting;

  CorrectionService(Posting posting) {
    this.posting = posting;
  }

  /**
   * Posts a Reversing Correction of the original Transaction, booked now. A Transaction is reversed
   * at most once; the unique index on the link settles two reversals that race.
   */
  @Transactional
  PostedTransaction reverse(TransactionId original) {
    PostedTransaction mistake =
        posting
            .find(original)
            .orElseThrow(() ->
                CorrectionProblems.notFound("No Transaction " + original.value() + " exists"));
    if (posting.isReversed(original)) {
      throw alreadyReversed(original);
    }
    TransactionId correction;
    try {
      correction = posting.post(NewTransaction.reversingCorrection(mistake));
    } catch (DuplicateKeyException raced) {
      throw alreadyReversed(original);
    }
    return posting.find(correction).orElseThrow();
  }

  private static RuntimeException alreadyReversed(TransactionId original) {
    return CorrectionProblems.alreadyReversed(
        "Transaction " + original.value() + " has already been reversed");
  }
}
