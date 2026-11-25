package com.paymentledger.ledger.shared;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A broken request the ledger refuses, answered as an RFC 9457 problem of a {@link ProblemType}
 * from the shared catalogue. Thrown before anything is stored.
 */
public final class Problem extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ProblemType type;
  private final @Nullable String field;

  private Problem(ProblemType type, String detail) {
    this(type, detail, null);
  }

  private Problem(ProblemType type, String detail, @Nullable String field) {
    super(detail);
    this.type = Objects.requireNonNull(type, "type");
    this.field = field;
  }

  /**
   * The request is about a field that is missing or wrong, named by its path in the JSON request
   * body, such as {@code amountPence} or {@code payee.sortCode}, so a client can show the problem
   * beside it.
   */
  public static Problem validationFailed(String field, String detail) {
    return new Problem(ProblemType.VALIDATION_FAILED, detail, Objects.requireNonNull(field));
  }

  /** Something the request names, at the field's path in the JSON request body, doesn't exist. */
  public static Problem notFound(String field, String detail) {
    return new Problem(ProblemType.NOT_FOUND, detail, Objects.requireNonNull(field));
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

  public static Problem seedDataReadOnly(String detail) {
    return new Problem(ProblemType.SEED_DATA_READ_ONLY, detail);
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

  /** Refuses an amount of zero or less as validation-failed, naming the field that holds it. */
  public static Money requirePositive(Money amount, String field) {
    if (!amount.isPositive()) {
      throw validationFailed(field, "The amount must be more than zero");
    }
    return amount;
  }

  public ProblemType type() {
    return type;
  }

  /** The request field the problem is about, if it is about one. */
  public Optional<String> field() {
    return Optional.ofNullable(field);
  }

  public String detail() {
    return Objects.requireNonNull(getMessage());
  }
}
