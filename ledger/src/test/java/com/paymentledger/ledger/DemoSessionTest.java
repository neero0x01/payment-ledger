package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Spec §8, public demo: "Try as customer" creates a Demo Sandbox, a new Customer with two Customer
 * Accounts funded from Funding by real Transactions, and logs the visitor in as that Customer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class, Sessions.class})
class DemoSessionTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;

  @Test
  void try_as_customer_opens_two_funded_customer_accounts_owned_by_the_visitor() {
    DemoSessions.Started demo = DemoSessions.start(sessions, "CUSTOMER", DemoSessions.newIp());

    assertThat(demo.body().role()).isEqualTo("CUSTOMER");
    assertThat(demo.body().accountIds()).hasSize(2);
    for (UUID account : demo.body().accountIds()) {
      assertThat(balancePence(demo.client(), account)).isPositive();
    }
  }

  @Test
  void try_as_customer_funds_the_sandbox_from_funding() {
    RestTestClient operator = sessions.operator();
    long fundingBefore = balancePence(operator, ledger.funding().value());

    DemoSessions.Started demo = DemoSessions.start(sessions, "CUSTOMER", DemoSessions.newIp());

    long sandbox =
        demo.body().accountIds().stream()
            .mapToLong(account -> balancePence(operator, account))
            .sum();
    assertThat(balancePence(operator, ledger.funding().value())).isEqualTo(fundingBefore + sandbox);
  }

  @Test
  void every_visitor_gets_their_own_sandbox() {
    DemoSessions.Started first = DemoSessions.start(sessions, "CUSTOMER", DemoSessions.newIp());
    DemoSessions.Started second = DemoSessions.start(sessions, "CUSTOMER", DemoSessions.newIp());

    assertThat(second.body().customerId()).isNotEqualTo(first.body().customerId());
    second
        .client()
        .get()
        .uri("/v1/accounts/{id}/balance", first.body().accountIds().getFirst())
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  void demo_customer_sees_their_session_as_a_customer() {
    DemoSessions.Started demo = DemoSessions.start(sessions, "CUSTOMER", DemoSessions.newIp());

    demo.client()
        .get()
        .uri("/v1/session")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.role")
        .isEqualTo("CUSTOMER")
        .jsonPath("$.customerId")
        .isEqualTo(String.valueOf(demo.body().customerId()));
  }

  static long balancePence(RestTestClient client, UUID account) {
    Balance balance =
        client
            .get()
            .uri("/v1/accounts/{id}/balance", account)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(Balance.class)
            .returnResult()
            .getResponseBody();
    assertThat(balance).isNotNull();
    return balance.balancePence();
  }

  record Balance(long balancePence) {}

  record DemoSession(String role, @Nullable UUID customerId, List<UUID> accountIds) {}
}
