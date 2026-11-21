package com.paymentledger.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The bare transfer page needs two known Accounts to show when the ledger runs locally under Docker
 * Compose (the {@code local} profile), and their Customer logs in as {@code local-customer}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("local")
@Import({TestcontainersConfiguration.class, LedgerFixture.class, Sessions.class})
class LocalDemoAccountsTest {

  private static final String ACCOUNT_A = "019a0000-0000-7000-8000-00000000000a";
  private static final String ACCOUNT_B = "019a0000-0000-7000-8000-00000000000b";

  @Autowired Sessions sessions;

  RestTestClient customer;

  @BeforeEach
  void givenTheLocalCustomerHasLoggedIn() {
    customer = sessions.logIn("local-customer", "local-customer");
  }

  @Test
  void local_profile_opens_account_a_with_100_pounds() {
    customer
        .get()
        .uri("/v1/accounts/{id}/balance", ACCOUNT_A)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.balancePence")
        .isEqualTo(100_00);
  }

  @Test
  void local_profile_opens_account_b_with_nothing() {
    customer
        .get()
        .uri("/v1/accounts/{id}/balance", ACCOUNT_B)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.balancePence")
        .isEqualTo(0);
  }
}
