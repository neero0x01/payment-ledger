package com.paymentledger.fpssimulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** The simulator takes Scheme Submissions and answers each later with a signed Scheme Outcome. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(SimulatorTestConfiguration.class)
class SchemeSubmissionTest {

  @Autowired RestTestClient http;
  @Autowired LedgerStub ledger;

  @BeforeEach
  void instantOutcomes() {
    settings(1.0, 0.0, 0, 0);
  }

  @Test
  void accepts_a_submission_with_202_and_calls_back_with_a_signed_acsc() {
    String endToEndId = newEndToEndId();

    submit(endToEndId, "12345678").expectStatus().isEqualTo(HttpStatus.ACCEPTED);

    LedgerStub.Callback callback = ledger.awaitCallbackFor(endToEndId);
    assertThat(callback.status()).isEqualTo("ACSC");
    assertThat(callback.reasonCode()).isNull();
    assertThat(callback.signature()).isEqualTo(hmacSha256Hex(callback.body()));
  }

  RestTestClient.ResponseSpec submit(String endToEndId, String creditorAccountNumber) {
    return http.post()
        .uri("/scheme/v1/submissions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"endToEndId": "%s", "debtorAccountId": "%s", "creditorSortCode": "040004",
             "creditorAccountNumber": "%s", "creditorName": "Jane Doe", "amountPence": 4000}
            """
                .formatted(endToEndId, UUID.randomUUID(), creditorAccountNumber))
        .exchange();
  }

  void settings(double acceptRate, double blackHoleRate, long latencyP50Ms, long latencyP99Ms) {
    http.put()
        .uri("/admin/settings")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"acceptRate": %s, "blackHoleRate": %s, "latencyP50Ms": %d, "latencyP99Ms": %d}
            """
                .formatted(acceptRate, blackHoleRate, latencyP50Ms, latencyP99Ms))
        .exchange()
        .expectStatus()
        .isOk();
  }

  static String newEndToEndId() {
    return "E2E" + Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36);
  }

  /** HMAC-SHA256 of the body under the shared key, as lowercase hex: the ledger's check. */
  static String hmacSha256Hex(String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              SimulatorTestConfiguration.HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
