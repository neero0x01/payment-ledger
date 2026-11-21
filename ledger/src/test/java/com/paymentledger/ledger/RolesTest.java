package com.paymentledger.ledger;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.util.List;
import java.util.UUID;
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

  @Test
  void operator_making_a_payment_is_forbidden_and_stores_nothing() {
    expectForbidden(
        operator
            .post()
            .uri("/v1/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": 1000}
                """
                    .formatted(a.value(), b.value()))
            .exchange());

    expectBalance(operator, a, 100_00);
    expectBalance(operator, b, 0);
  }

  @Test
  void customer_reading_another_customers_balance_is_forbidden() {
    RestTestClient ownerOfB = sessions.customerOwning(b);

    expectForbidden(ownerOfB.get().uri("/v1/accounts/{id}/balance", a.value()).exchange());
    expectForbidden(
        ownerOfB.get().uri("/v1/accounts/{id}/balance", ledger.funding().value()).exchange());
    expectBalance(ownerOfB, b, 0);
  }

  @Test
  void customer_reading_another_customers_payment_is_forbidden() {
    RestTestClient ownerOfA = sessions.customerOwning(a);
    String payment = payAndReturnLocation(ownerOfA, a, b, 30_00);

    expectForbidden(sessions.customerOwning(b).get().uri(payment).exchange());
    ownerOfA.get().uri(payment).exchange().expectStatus().isOk();
  }

  @Test
  void operator_can_read_any_payment() {
    String payment = payAndReturnLocation(sessions.customerOwning(a), a, b, 30_00);

    operator
        .get()
        .uri(payment)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("COMPLETED");
  }

  @Test
  void customer_reading_a_transaction_is_forbidden_even_their_own() {
    RestTestClient ownerOfA = sessions.customerOwning(a);
    String transaction =
        ownerOfA
            .get()
            .uri(payAndReturnLocation(ownerOfA, a, b, 30_00))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(PaymentView.class)
            .returnResult()
            .getResponseBody()
            .transactionIds()
            .getFirst()
            .toString();

    expectForbidden(ownerOfA.get().uri("/v1/transactions/{id}", transaction).exchange());
    operator.get().uri("/v1/transactions/{id}", transaction).exchange().expectStatus().isOk();
  }

  private static String payAndReturnLocation(
      RestTestClient payer, AccountId from, AccountId to, long amountPence) {
    return payer
        .post()
        .uri("/v1/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": %d}
            """
                .formatted(from.value(), to.value(), amountPence))
        .exchange()
        .expectStatus()
        .isCreated()
        .returnResult()
        .getResponseHeaders()
        .getFirst("Location");
  }

  record PaymentView(List<UUID> transactionIds) {}

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
