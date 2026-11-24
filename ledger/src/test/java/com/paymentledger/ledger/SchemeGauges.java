package com.paymentledger.ledger;

import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the scheme metrics the way an Operator does, at /actuator/metrics. */
final class SchemeGauges {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private SchemeGauges() {}

  /** How many Faster Payments are Stuck Payments now. */
  static long stuckPayments(RestTestClient operator) {
    return (long) measurement(operator, "ledger.payments.stuck");
  }

  /** How many contradictory Scheme Outcomes the ledger has ignored. */
  static long schemeAnomalies(RestTestClient operator) {
    return (long) measurement(operator, "ledger.scheme.anomalies");
  }

  private static double measurement(RestTestClient operator, String name) {
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
