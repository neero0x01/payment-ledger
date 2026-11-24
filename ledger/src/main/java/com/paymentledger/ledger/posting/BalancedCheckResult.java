package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * What the last balanced check found (spec §13): whether the ledger was a Balanced Ledger, the
 * total drift it found, and when the run started.
 */
public record BalancedCheckResult(boolean balanced, Money imbalance, Instant startedAt) {

  public BalancedCheckResult {
    Objects.requireNonNull(imbalance, "imbalance");
    Objects.requireNonNull(startedAt, "startedAt");
  }
}
