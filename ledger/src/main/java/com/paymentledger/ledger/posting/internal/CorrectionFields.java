package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.posting.CorrectionDetails;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** A Correction's reason and note as flat nullable fields, for SQL parameters and responses. */
record CorrectionFields(@Nullable String reason, @Nullable String note) {

  static CorrectionFields of(Optional<CorrectionDetails> correction) {
    return correction
        .map(details -> new CorrectionFields(details.reason().name(), details.note()))
        .orElse(new CorrectionFields(null, null));
  }
}
