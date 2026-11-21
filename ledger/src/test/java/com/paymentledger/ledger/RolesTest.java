package com.paymentledger.ledger;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Spec §8 roles: a Customer sees and pays only from their own Accounts; an Operator sees everything
 * and posts Corrections, but makes no Payments.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class, Sessions.class})
class RolesTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;

  AccountId a;
  AccountId b;
  RestTestClient operator;

  @BeforeEach
  void givenAHas100PoundsAndBHasNothing() {
    a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    b = ledger.customerAccountWithBalance(Money.ofPence(0));
    operator = sessions.operator();
  }

  @Test
  void operator_can_read_the_balance_of_every_account() {
    expectBalance(operator, a, 100_00);
    expectBalance(operator, b, 0);
    operator
        .get()
        .uri("/v1/accounts/{id}/balance", ledger.funding().value())
        .exchange()
        .expectStatus()
        .isOk();
  }

  /** Scenario 7 [M3]. */
  @Test
  void customer_posting_a_correction_is_forbidden_and_posts_nothing() {
    RestTestClient customer = sessions.customerOwning(a);

    expectForbidden(
        customer
            .post()
            .uri("/v1/corrections")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"kind": "ADJUSTMENT", "reason": "GOODWILL", "note": "Paying myself",
                 "accountId": "%s", "amountPence": 500, "direction": "CREDIT"}
                """
                    .formatted(a.value()))
            .exchange());

    expectBalance(operator, a, 100_00);
  }

  private static void expectForbidden(RestTestClient.ResponseSpec response) {
    response
        .expectStatus()
        .isForbidden()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/forbidden");
  }

  private static void expectBalance(RestTestClient client, AccountId account, long pence) {
    client
        .get()
        .uri("/v1/accounts/{id}/balance", account.value())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.balancePence")
        .isEqualTo(pence);
  }
}
