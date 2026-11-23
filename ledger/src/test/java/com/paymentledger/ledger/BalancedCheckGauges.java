package com.paymentledger.ledger;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the balanced-check gauges the way an Operator does, at /actuator/metrics. */
final class BalancedCheckGauges {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Duration RUNS = Duration.ofSeconds(30);

  private BalancedCheckGauges() {}

  /** ledger_balanced: 1 when the last run found the ledger balanced, 0 when it found drift. */
  static double balanced(RestTestClient operator) {
    return gauge(operator, "ledger.balanced");
  }

  /** ledger_imbalance_pence: the total drift, in pence, the last run found. */
  static long imbalancePence(RestTestClient operator) {
    return (long) gauge(operator, "ledger.imbalance.pence");
  }

  /** When the last run started. */
  static Instant lastRun(RestTestClient operator) {
    double epochSeconds = gauge(operator, "ledger.balanced.last.run");
    return Instant.ofEpochMilli((long) (epochSeconds * 1000));
  }

  /**
   * Waits for a run that started after this call and has finished. The run finishing next may have
   * started before, so this waits for the one after it.
   */
  static void awaitRunStartedAfterNow(RestTestClient operator) {
    for (int i = 0; i < 2; i++) {
      Instant previous = lastRun(operator);
      await().atMost(RUNS).until(() -> lastRun(operator).isAfter(previous));
    }
  }

  private static double gauge(RestTestClient operator, String name) {
    String body =
        operator
            .get()
            .uri("/actuator/metrics/{name}", name)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    JsonNode metric = JSON.readTree(body);
    return metric.get("measurements").get(0).get("value").asDouble();
  }
}
