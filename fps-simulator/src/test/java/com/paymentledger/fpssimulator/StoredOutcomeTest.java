package com.paymentledger.fpssimulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The simulator keeps its outcomes in its own database, so a restarted simulator still answers a
 * re-sent submission with the outcome it decided before.
 */
class StoredOutcomeTest {

  @Test
  void a_restarted_simulator_answers_a_resent_submission_with_the_stored_outcome() {
    LedgerStub ledger = new LedgerStub();
    String endToEndId = SchemeSubmissionTest.newEndToEndId();

    try (ConfigurableApplicationContext first = simulator(ledger, "1.0")) {
      submit(first, endToEndId);
      ledger.awaitOutcomeFor(endToEndId);
    }
    // The restarted simulator would reject anything it decided afresh.
    try (ConfigurableApplicationContext restarted = simulator(ledger, "0.0")) {
      submit(restarted, endToEndId);

      List<LedgerStub.ReceivedOutcome> callbacks = ledger.awaitOutcomesFor(endToEndId, 2);
      assertThat(callbacks)
          .extracting(LedgerStub.ReceivedOutcome::status)
          .containsExactly("ACSC", "ACSC");
    }
  }

  private static ConfigurableApplicationContext simulator(LedgerStub ledger, String acceptRate) {
    return new SpringApplicationBuilder(FpsSimulatorApplication.class)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + SimulatorTestConfiguration.POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + SimulatorTestConfiguration.POSTGRES.getUsername(),
            "--spring.datasource.password=" + SimulatorTestConfiguration.POSTGRES.getPassword(),
            "--FPSSIM_LEDGER_BASE_URL=" + ledger.baseUrl(),
            "--FPSSIM_CALLBACK_HMAC_KEY=" + SimulatorTestConfiguration.HMAC_KEY,
            "--FPSSIM_ACCEPT_RATE=" + acceptRate,
            "--FPSSIM_LATENCY_P50_MS=0",
            "--FPSSIM_LATENCY_P99_MS=0");
  }

  private static void submit(ConfigurableApplicationContext simulator, String endToEndId) {
    int port = ((WebServerApplicationContext) simulator).getWebServer().getPort();
    RestTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .build()
        .post()
        .uri("/scheme/v1/submissions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"endToEndId": "%s", "debtorAccountId": "%s", "creditorSortCode": "040004",
             "creditorAccountNumber": "12345678", "creditorName": "Jane Doe", "amountPence": 4000}
            """
                .formatted(endToEndId, UUID.randomUUID()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.ACCEPTED);
  }
}
