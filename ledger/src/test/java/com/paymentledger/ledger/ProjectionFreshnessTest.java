package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Projection Freshness on the Operator dashboard [M6]: while the Read Model has not applied an
 * event, the dashboard shows it trailing the ledger by at least as long as that event has waited.
 * The test stops the projector, once it has migrated the Read Model, so the Read Model never
 * catches up; the context has its own Postgres and Kafka, so no other context's projector applies
 * these events.
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
class ProjectionFreshnessTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;

  @Autowired
  @Qualifier(ProjectorConfiguration.PROJECTOR)
  ConfigurableApplicationContext projector;

  @Test
  void read_model_that_has_not_applied_a_payment_trails_by_as_long_as_it_has_waited() {
    projector.close();
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient operator = sessions.operator();
    sessions
        .customerOwning(a)
        .post()
        .uri("/v1/payments")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": 1000}
            """
                .formatted(a.value(), b.value()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
    long paidAt = System.nanoTime();

    double freshness =
        await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> projectionFreshnessSeconds(operator), seconds -> seconds >= 3);
    double waited = (System.nanoTime() - paidAt) / 1e9;

    // At least as long as the Payment has waited since this test saw it made, and no more than
    // since the first funding, made just before it.
    assertThat(freshness).isGreaterThanOrEqualTo(3).isLessThan(waited + 10);
  }

  private static double projectionFreshnessSeconds(RestTestClient operator) {
    String body =
        operator
            .get()
            .uri("/v1/dashboard")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    return JSON.readTree(body).path("projectionFreshnessSeconds").asDouble(-1);
  }
}
