package com.paymentledger.ledger;

import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the Entry partition gauge the way an Operator does, at /actuator/metrics. */
final class PartitionGauges {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private PartitionGauges() {}

  /** 1 when next month has no Entry partition, 0 when it has one. */
  static double nextMonthMissing(RestTestClient operator) {
    String body =
        operator
            .get()
            .uri("/actuator/metrics/{name}", "ledger.entry.partition.next.month.missing")
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
