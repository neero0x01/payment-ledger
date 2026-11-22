package com.paymentledger.ledger.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The SHA-256 of a request's canonical form, as hex, stored beside its Idempotency-Key so that a
 * retry can tell the same request from a different one sent with the same key (ADR 0007).
 */
public record RequestHash(String value) {

  public RequestHash {
    Objects.requireNonNull(value, "value");
  }

  /** The hash of the request's canonical fields, one per line, in the order given. */
  public static RequestHash of(String... fields) {
    String canonical = String.join("\n", fields);
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return new RequestHash(HexFormat.of().formatHex(hash));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java platform has SHA-256", e);
    }
  }

  /**
   * Refuses a request sent with an Idempotency-Key that was already used, unless this hash, stored
   * with the key, is the request's own: the same key with a different body is
   * idempotency-key-reused.
   */
  public void requireSameRequest(RequestHash sent, String request) {
    if (!equals(sent)) {
      throw Problem.idempotencyKeyReused(
          "This Idempotency-Key was already used for a different " + request + " request");
    }
  }
}
