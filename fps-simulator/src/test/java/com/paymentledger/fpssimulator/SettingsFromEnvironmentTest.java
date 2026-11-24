package com.paymentledger.fpssimulator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

/** The simulator starts with the settings its environment variables give. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "FPSSIM_ACCEPT_RATE=0.5",
      "FPSSIM_BLACK_HOLE_RATE=0.25",
      "FPSSIM_LATENCY_P50_MS=300",
      "FPSSIM_LATENCY_P99_MS=4000"
    })
@AutoConfigureRestTestClient
@Import(SimulatorTestConfiguration.class)
class SettingsFromEnvironmentTest {

  @Autowired RestTestClient http;

  @Test
  void starts_with_the_accept_black_hole_and_latency_settings_of_its_environment() {
    http.get()
        .uri("/admin/settings")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .json(
            """
            {"acceptRate": 0.5, "blackHoleRate": 0.25, "latencyP50Ms": 300, "latencyP99Ms": 4000}
            """);
  }
}
