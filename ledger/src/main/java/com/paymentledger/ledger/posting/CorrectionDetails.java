package com.paymentledger.ledger.posting;

import java.util.Objects;

/** What every Correction carries: its Correction Reason and the Operator's note. */
public record CorrectionDetails(CorrectionReason reason, String note) {

  public CorrectionDetails {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(note, "note");
    if (note.isBlank()) {
      throw new IllegalArgumentException("A Correction's note must not be blank");
    }
  }
}
