package com.paymentledger.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The bare transfer page needs two known Accounts to show when the ledger runs locally under Docker
 * Compose (the {@code local} profile).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class LocalDemoAccountsTest {

  private static final String ACCOUNT_A = "019a0000-0000-7000-8000-00000000000a";
  private static final String ACCOUNT_B = "019a0000-0000-7000-8000-00000000000b";

  @Autowired RestTestClient http;

  @Test
  void local_profile_opens_account_a_with_100_pounds() {
    http.get()
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
    http.get()
        .uri("/v1/accounts/{id}/balance", ACCOUNT_B)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.balancePence")
        .isEqualTo(0);
  }
}
