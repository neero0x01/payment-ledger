package com.paymentledger.loadtest;

import java.time.Duration;

/**
 * What a run is pointed at and how hard it pushes, from system properties ({@code -DbaseUrl=…} on
 * the Maven command line). The load-test password comes from the {@code LOADTEST_PASSWORD}
 * environment variable, the one the seed gave every {@code load-test-NNN} login, and never from the
 * command line or the repository.
 *
 * @param baseUrl the ledger, or Caddy in front of it
 * @param paymentsPerSecond the rate held once the ramp is over
 * @param rampUp how long the rate takes to climb to {@code paymentsPerSecond}
 * @param hold how long the rate is held: 10 minutes for a measured run (spec §12)
 * @param password the load-test Customers' shared password
 */
record LoadTestSettings(
    String baseUrl, double paymentsPerSecond, Duration rampUp, Duration hold, String password) {

  static LoadTestSettings fromEnvironment() {
    String password = System.getenv("LOADTEST_PASSWORD");
    if (password == null || password.isBlank()) {
      throw new IllegalStateException(
          "Set LOADTEST_PASSWORD to the load-test password the seed was given");
    }
    return new LoadTestSettings(
        System.getProperty("baseUrl", "http://localhost:8080"),
        Double.parseDouble(System.getProperty("paymentsPerSecond", "50")),
        Duration.ofSeconds(Long.parseLong(System.getProperty("rampUpSeconds", "30"))),
        Duration.ofSeconds(Long.parseLong(System.getProperty("holdSeconds", "60"))),
        password);
  }
}
