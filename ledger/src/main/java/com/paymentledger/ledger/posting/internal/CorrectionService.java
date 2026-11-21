package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.TransactionId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Posts Corrections: new Transactions that fix mistakes without changing the originals. */
@Service
class CorrectionService {

  private final Posting posting;

  CorrectionService(Posting posting) {
    this.posting = posting;
  }

  /** Posts a Reversing Correction of the original Transaction, booked now. */
  @Transactional
  PostedTransaction reverse(TransactionId original) {
    PostedTransaction mistake =
        posting
            .find(original)
            .orElseThrow(() ->
                CorrectionProblems.notFound("No Transaction " + original.value() + " exists"));
    TransactionId correction = posting.post(NewTransaction.reversingCorrection(mistake));
    return posting.find(correction).orElseThrow();
  }
}
