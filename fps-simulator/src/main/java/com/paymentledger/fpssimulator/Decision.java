package com.paymentledger.fpssimulator;

import java.util.Objects;
import java.util.Optional;

/**
 * What the simulator decided for a Scheme Submission: the Scheme Outcome it sends, or none at all
 * when it black-holes the submission.
 */
record Decision(Optional<SchemeOutcome> outcome) {

  static final Decision BLACK_HOLE = new Decision(Optional.empty());

  Decision {
    Objects.requireNonNull(outcome, "outcome");
  }

  static Decision send(SchemeOutcome outcome) {
    return new Decision(Optional.of(outcome));
  }
}
