package com.paymentledger.loadtest;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.core.Assertion;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Simulation;
import java.util.List;
import java.util.stream.Stream;

/**
 * What both runs share: the load-test Customers log in first, then virtual users arrive at a rising
 * and then steady rate, one Payment each (spec §12). A run passes on p99 under 200 ms for every
 * {@code POST /v1/payments} and under 0.1% of requests failing. The third pass mark, outbox lag
 * under 1 s, is on the Grafana dashboard, not in the Gatling report.
 */
abstract class LedgerSimulation extends Simulation {

  private static final int P99_LIMIT_MS = 200;
  private static final double FAILED_PERCENT_LIMIT = 0.1;

  private final LoadTestSettings settings = LoadTestSettings.fromEnvironment();
  final LoadTestCustomers.LoggedInCustomers customers = LoadTestCustomers.logIn(settings);

  /**
   * @param name the scenario's name in the report
   * @param payment what each virtual user does
   * @param paymentRequests the names of the {@code POST /v1/payments} requests it makes, each held
   *     to the latency limit
   */
  void run(String name, ChainBuilder payment, List<String> paymentRequests) {
    double rate = settings.paymentsPerSecond();
    List<Assertion> assertions =
        Stream.concat(
                paymentRequests.stream()
                    .map(request ->
                        details(request).responseTime().percentile(99.0).lt(P99_LIMIT_MS)),
                Stream.of(global().failedRequests().percent().lt(FAILED_PERCENT_LIMIT)))
            .toList();
    setUp(
            scenario(name)
                .feed(LedgerRequests.feeder(customers.paying()))
                .exec(payment)
                .injectOpen(
                    rampUsersPerSec(1).to(rate).during(settings.rampUp()),
                    constantUsersPerSec(rate).during(settings.hold())))
        .protocols(http.baseUrl(settings.baseUrl()).acceptHeader("application/json"))
        .assertions(assertions);
  }
}
