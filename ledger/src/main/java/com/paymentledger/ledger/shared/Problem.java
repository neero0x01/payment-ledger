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

  /** The Transaction belongs to a Payment still awaiting its final outcome. */
  public static Problem paymentPending(String detail) {
    return new Problem(ProblemType.PAYMENT_PENDING, detail);
  }

  /** A request that must be safe to retry came without an Idempotency-Key header. */
  public static Problem idempotencyKeyMissing(String detail) {
    return new Problem(ProblemType.IDEMPOTENCY_KEY_MISSING, detail);
  }

  /** The Idempotency-Key was already used by the same principal for a different request. */
  public static Problem idempotencyKeyReused(String detail) {
    return new Problem(ProblemType.IDEMPOTENCY_KEY_REUSED, detail);
  }

  /** The request has no session, or a login's credentials are wrong. */
  public static Problem unauthenticated(String detail) {
    return new Problem(ProblemType.UNAUTHENTICATED, detail);
  }

  /** The Principal may not do this, or the request lacks its CSRF token. */
  public static Problem forbidden(String detail) {
    return new Problem(ProblemType.FORBIDDEN, detail);
  }

  /** The request goes beyond one of the public demo's abuse limits. */
  public static Problem rateLimited(String detail) {
    return new Problem(ProblemType.RATE_LIMITED, detail);
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
