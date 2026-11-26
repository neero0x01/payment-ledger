package com.paymentledger.loadtest;

import java.util.List;

/**
 * The hot-account run (spec §12): 100% Faster Payments. Every one credits FPS Clearing, so every
 * Payment waits its turn for that one balance row lock (ADR 0004); this run measures what that
 * contention costs.
 */
public class HotAccountSimulation extends LedgerSimulation {

  {
    run("Hot account", LedgerRequests.FASTER_PAYMENT, List.of("Faster Payment"));
  }
}
