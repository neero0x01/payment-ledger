package com.paymentledger.ledger.payments;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The scheme's final answer to the Scheme Submission with the End-to-End Id: accepted, or rejected
 * with a reason code.
 */
public record SchemeOutcome(
    EndToEndId endToEndId, Status status, @Nullable SchemeReasonCode reasonCode) {

  public SchemeOutcome {
    Objects.requireNonNull(endToEndId, "endToEndId");
    Objects.requireNonNull(status, "status");
    if ((status == Status.RJCT) != (reasonCode != null)) {
      throw new IllegalArgumentException(
          "A rejected Scheme Outcome, and only one, has a reason code");
    }
  }

  /** The reason code of a Scheme Rejection. */
  public Optional<SchemeReasonCode> rejection() {
    return Optional.ofNullable(reasonCode);
  }

  /** The ISO 20022 transaction status: accepted (ACSC) or rejected (RJCT). */
  public enum Status {
    ACSC,
    RJCT
  }
}
