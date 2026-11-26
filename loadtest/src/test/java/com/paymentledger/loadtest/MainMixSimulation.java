package com.paymentledger.loadtest;

import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.randomSwitch;

import java.util.List;

/**
 * The main mix (spec §12): 70% Internal Transfers between random load-test Accounts, 15% Faster
 * Payments, 5% retries with a reused Idempotency-Key, 5% Payments that fail for Insufficient Funds,
 * and 5% Balance and Statement reads. N is the highest rate this holds for 10 minutes.
 */
public class MainMixSimulation extends LedgerSimulation {

  {
    run(
        "Main mix",
        exec(
            randomSwitch()
                .on(
                    percent(70.0).then(LedgerRequests.INTERNAL_TRANSFER),
                    percent(15.0).then(LedgerRequests.FASTER_PAYMENT),
                    percent(5.0).then(LedgerRequests.RETRY),
                    percent(5.0).then(LedgerRequests.insufficientFunds(customers.belowMaximum())),
                    percent(5.0).then(LedgerRequests.READS))),
        List.of(
            "Internal Transfer",
            "Faster Payment",
            "Retry: first send",
            "Retry: replay",
            "Insufficient Funds"));
  }
}
