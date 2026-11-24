package com.paymentledger.ledger;

import static com.paymentledger.ledger.DemoLimitsTest.expectRateLimited;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Spec §8 abuse limits: at most so many Demo Sandboxes a day across every visitor (500 in
 * production; 2 here, so the test needn't create 500).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ledger.demo.sandboxes-per-day=2")
@AutoConfigureRestTestClient
@Import({
  TestcontainersConfiguration.class,
  LedgerFixture.class,
  Sessions.class,
  MutableClock.Configuration.class
})
class DemoDailyLimitTest {

  @Autowired Sessions sessions;
  @Autowired MutableClock clock;

  @Test
  void sandbox_beyond_the_daily_limit_is_refused_until_a_day_has_passed() {
    DemoSessions.start(sessions, "CUSTOMER", DemoSessions.newIp());
    clock.advance(Duration.ofHours(1));
    DemoSessions.start(sessions, "CUSTOMER", DemoSessions.newIp());

    expectRateLimited(DemoSessions.post(sessions, "CUSTOMER", DemoSessions.newIp()));

    clock.advance(Duration.ofHours(23));
    DemoSessions.start(sessions, "CUSTOMER", DemoSessions.newIp());
    expectRateLimited(DemoSessions.post(sessions, "CUSTOMER", DemoSessions.newIp()));
  }
}
