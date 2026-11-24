package com.paymentledger.ledger;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Screen 4, the Operator dashboard [M6]: GET /v1/dashboard serves the per-minute Read Model figures
 * the projector builds from PaymentStatusChanged events, with the outbox lag, Projection Freshness,
 * balanced-check and Stuck Payment gauges.
 *
 * <p>The context has its own Postgres and Kafka, closed after the class, so the figures count only
 * this class's Payments.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({
  DedicatedContainersConfiguration.class,
  ProjectorConfiguration.class,
  LedgerFixture.class,
  Sessions.class
})
@DirtiesContext
class DashboardTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;

  @Test
  void only_an_operator_sees_the_dashboard() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(0));

    sessions
        .customerOwning(a)
        .get()
        .uri("/v1/dashboard")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/forbidden");
    sessions.operator().get().uri("/v1/dashboard").exchange().expectStatus().isOk();
  }
}
