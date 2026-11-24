package com.paymentledger.ledger;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Spec §8 abuse limits on the public demo: one sandbox per IP address per 10 minutes, and 30 POSTs
 * per minute per demo session. Over a limit the ledger answers 429 with a rate-limited problem.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({
  TestcontainersConfiguration.class,
  LedgerFixture.class,
  Sessions.class,
  MutableClock.Configuration.class
})
class DemoLimitsTest {

  @Autowired Sessions sessions;
  @Autowired MutableClock clock;

  @Test
  void second_sandbox_from_the_same_ip_within_10_minutes_is_refused_as_rate_limited() {
    String ip = DemoSessions.newIp();
    DemoSessions.start(sessions, "CUSTOMER", ip);
    clock.advance(Duration.ofMinutes(9));

    expectRateLimited(DemoSessions.post(sessions, "CUSTOMER", ip));
  }

  @Test
  void sandbox_from_the_same_ip_is_allowed_again_after_10_minutes() {
    String ip = DemoSessions.newIp();
    DemoSessions.start(sessions, "CUSTOMER", ip);
    clock.advance(Duration.ofMinutes(10));

    DemoSessions.start(sessions, "CUSTOMER", ip);
  }

  @Test
  void sandbox_from_another_ip_is_allowed_at_once() {
    DemoSessions.start(sessions, "CUSTOMER", DemoSessions.newIp());

    DemoSessions.start(sessions, "CUSTOMER", DemoSessions.newIp());
  }

  @Test
  void thirty_first_post_within_a_minute_of_a_demo_session_is_refused_as_rate_limited() {
    DemoSessions.Started demo = DemoSessions.start(sessions, "CUSTOMER", DemoSessions.newIp());
    for (int i = 0; i < 30; i++) {
      pay1p(demo).expectStatus().isEqualTo(HttpStatus.CREATED);
    }

    expectRateLimited(pay1p(demo));
  }

  @Test
  void demo_session_may_post_again_once_the_minute_has_passed() {
    DemoSessions.Started demo = DemoSessions.start(sessions, "CUSTOMER", DemoSessions.newIp());
    for (int i = 0; i < 30; i++) {
      pay1p(demo).expectStatus().isEqualTo(HttpStatus.CREATED);
    }
    clock.advance(Duration.ofMinutes(1));

    pay1p(demo).expectStatus().isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void posts_by_one_demo_session_do_not_use_up_another_sessions_limit() {
    DemoSessions.Started first = DemoSessions.start(sessions, "CUSTOMER", DemoSessions.newIp());
    DemoSessions.Started second = DemoSessions.start(sessions, "CUSTOMER", DemoSessions.newIp());
    for (int i = 0; i < 30; i++) {
      pay1p(first).expectStatus().isEqualTo(HttpStatus.CREATED);
    }

    pay1p(second).expectStatus().isEqualTo(HttpStatus.CREATED);
  }

  private static RestTestClient.ResponseSpec pay1p(DemoSessions.Started demo) {
    return demo.client()
        .post()
        .uri("/v1/payments")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": 1}
            """
                .formatted(demo.body().accountIds().get(0), demo.body().accountIds().get(1)))
        .exchange();
  }

  static void expectRateLimited(RestTestClient.ResponseSpec response) {
    response
        .expectStatus()
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/rate-limited")
        .jsonPath("$.detail")
        .isNotEmpty();
  }
}
