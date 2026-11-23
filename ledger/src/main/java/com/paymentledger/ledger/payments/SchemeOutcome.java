package com.paymentledger.ledger.payments;

import java.util.Objects;

/** The scheme's final answer to the Scheme Submission with the End-to-End Id. */
public record SchemeOutcome(EndToEndId endToEndId, Status status) {

  public SchemeOutcome {
    Objects.requireNonNull(endToEndId, "endToEndId");
    Objects.requireNonNull(status, "status");
  }

  /** The ISO 20022 transaction status: accepted (ACSC) or rejected (RJCT). */
  public enum Status {
    ACSC,
    RJCT
  }
}
