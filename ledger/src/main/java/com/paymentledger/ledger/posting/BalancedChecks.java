package com.paymentledger.ledger.posting;

import java.util.Optional;

/** The balanced check's results, for Operators. */
public interface BalancedChecks {

  /** What the last balanced check found, or empty before its first run. */
  Optional<BalancedCheckResult> last();
}
