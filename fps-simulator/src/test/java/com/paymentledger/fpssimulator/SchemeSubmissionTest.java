package com.paymentledger.fpssimulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
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

  @Test
  void rejects_with_a_reason_code_when_the_accept_rate_is_zero() {
    settings(0.0, 0.0, 0, 0);
    String endToEndId = newEndToEndId();

    submit(endToEndId, "12345678").expectStatus().isEqualTo(HttpStatus.ACCEPTED);

    LedgerStub.Callback callback = ledger.awaitCallbackFor(endToEndId);
    assertThat(callback.status()).isEqualTo("RJCT");
    assertThat(callback.reasonCode()).isIn("AC01", "AG01", "MS03");
    assertThat(callback.signature()).isEqualTo(hmacSha256Hex(callback.body()));
  }

  @Test
  void a_payee_account_number_ending_0401_always_gets_rjct_ac04() {
    String endToEndId = newEndToEndId();

    submit(endToEndId, "12340401").expectStatus().isEqualTo(HttpStatus.ACCEPTED);

    LedgerStub.Callback callback = ledger.awaitCallbackFor(endToEndId);
    assertThat(callback.status()).isEqualTo("RJCT");
    assertThat(callback.reasonCode()).isEqualTo("AC04");
  }

  @Test
  void a_resent_submission_gets_its_stored_outcome_again() {
    String endToEndId = newEndToEndId();
    submit(endToEndId, "12345678").expectStatus().isEqualTo(HttpStatus.ACCEPTED);
    ledger.awaitCallbackFor(endToEndId);

    // Were it decided afresh, it would now be rejected.
    settings(0.0, 0.0, 0, 0);
    submit(endToEndId, "12345678").expectStatus().isEqualTo(HttpStatus.ACCEPTED);

    List<LedgerStub.Callback> callbacks = ledger.awaitCallbacksFor(endToEndId, 2);
    assertThat(callbacks).extracting(LedgerStub.Callback::status).containsExactly("ACSC", "ACSC");
  }

  @Test
  void a_black_holed_submission_never_gets_an_outcome_even_when_resent()
      throws InterruptedException {
    settings(1.0, 1.0, 0, 0);
    String endToEndId = newEndToEndId();
    submit(endToEndId, "12345678").expectStatus().isEqualTo(HttpStatus.ACCEPTED);

    settings(1.0, 0.0, 0, 0);
    submit(endToEndId, "12345678").expectStatus().isEqualTo(HttpStatus.ACCEPTED);

    Thread.sleep(1_000);
    assertThat(ledger.callbacksFor(endToEndId)).isEmpty();
  }

  @Test
  void the_outcome_waits_for_the_latency_set() {
    settings(1.0, 0.0, 700, 700);
    String endToEndId = newEndToEndId();
    Instant submitted = Instant.now();

    submit(endToEndId, "12345678").expectStatus().isEqualTo(HttpStatus.ACCEPTED);

    LedgerStub.Callback callback = ledger.awaitCallbackFor(endToEndId);
    assertThat(Duration.between(submitted, callback.receivedAt()))
        .isGreaterThanOrEqualTo(Duration.ofMillis(700));
  }

  @Test
  void settings_outside_their_range_are_refused_as_a_problem() {
    http.put()
        .uri("/admin/settings")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"acceptRate": 1.5, "blackHoleRate": 0, "latencyP50Ms": 200, "latencyP99Ms": 100}
            """)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectHeader()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON);
  }

  @Test
  void a_submission_without_an_end_to_end_id_is_refused_as_a_problem() {
    http.post()
        .uri("/scheme/v1/submissions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"creditorSortCode": "040004", "creditorAccountNumber": "12345678",
             "creditorName": "Jane Doe", "amountPence": 4000}
            """)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectHeader()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON);
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
