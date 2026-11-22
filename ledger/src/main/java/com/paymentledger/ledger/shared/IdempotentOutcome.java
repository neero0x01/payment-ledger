package com.paymentledger.ledger.shared;

import java.util.Objects;
import java.util.function.Function;

/**
 * What a request sent with an Idempotency-Key led to, and whether it was replayed: a retry of a
 * request already carried out gets the stored outcome back and changes nothing (ADR 0007).
 */
public record IdempotentOutcome<T>(T outcome, boolean replayed) {

  /** The response header that marks a replayed outcome. */
  public static final String REPLAYED_HEADER = "Idempotent-Replayed";

  public IdempotentOutcome {
    Objects.requireNonNull(outcome, "outcome");
  }

  /** The outcome of the first request sent with its key. */
  public static <T> IdempotentOutcome<T> first(T outcome) {
    return new IdempotentOutcome<>(outcome, false);
  }

  /** The stored outcome of an earlier request with the same key. */
  public static <T> IdempotentOutcome<T> replay(T outcome) {
    return new IdempotentOutcome<>(outcome, true);
  }

  public <U> IdempotentOutcome<U> map(Function<? super T, ? extends U> mapper) {
    return new IdempotentOutcome<>(mapper.apply(outcome), replayed);
  }
}
