package com.paymentledger.ledger.shared;

import java.util.Objects;

/**
 * A broken request the ledger refuses, answered as an RFC 9457 problem of a {@link ProblemType}
 * from the shared catalogue. Thrown before anything is stored.
 */
public final class Problem extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ProblemType type;

  private Problem(ProblemType type, String detail) {
    super(detail);
    this.type = Objects.requireNonNull(type, "type");
  }

  public static Problem validationFailed(String detail) {
    return new Problem(ProblemType.VALIDATION_FAILED, detail);
  }

  public static Problem notFound(String detail) {
    return new Problem(ProblemType.NOT_FOUND, detail);
  }

  public static Problem alreadyReversed(String detail) {
    return new Problem(ProblemType.ALREADY_REVERSED, detail);
  }

  /** The request has no session, or a login's credentials are wrong. */
  public static Problem unauthenticated(String detail) {
    return new Problem(ProblemType.UNAUTHENTICATED, detail);
  }

  /** The Principal may not do this, or the request lacks its CSRF token. */
  public static Problem forbidden(String detail) {
    return new Problem(ProblemType.FORBIDDEN, detail);
  }

  /** Refuses an amount of zero or less as validation-failed. */
  public static Money requirePositive(Money amount) {
    if (!amount.isPositive()) {
      throw validationFailed("The amount must be more than zero");
    }
    return amount;
  }

  public ProblemType type() {
    return type;
  }

  public String detail() {
    return Objects.requireNonNull(getMessage());
  }
}
