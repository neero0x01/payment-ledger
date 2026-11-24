package com.paymentledger.ledger;

import static com.paymentledger.ledger.DemoSessionTest.balancePence;
import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Spec §8, public demo: "Try as operator" logs the visitor in as the demo Operator, who can correct
 * only Transactions on Demo Sandbox Accounts. Everything else, seeded data included, is read-only
 * to them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class, Sessions.class})
class DemoOperatorTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;

  RestTestClient demoOperator;
  DemoSessions.Started sandbox;

  @BeforeEach
  void givenAVisitorTriedAsCustomerAndAnotherAsOperator() {
    sandbox = DemoSessions.start(sessions, "CUSTOMER", DemoSessions.newIp());
    demoOperator = DemoSessions.start(sessions, "OPERATOR", DemoSessions.newIp()).client();
  }

  @Test
  void try_as_operator_logs_the_visitor_in_as_an_operator() {
    demoOperator
        .get()
        .uri("/v1/session")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.role")
        .isEqualTo("OPERATOR");
  }

  @Test
  void demo_operator_can_reverse_a_payment_between_demo_sandbox_accounts() {
    UUID current = sandbox.body().accountIds().get(0);
    UUID savings = sandbox.body().accountIds().get(1);
    long before = balancePence(demoOperator, current);
    UUID transfer = pay(current, savings, 10_00);

    correct(demoOperator, reversing(transfer)).expectStatus().isEqualTo(HttpStatus.CREATED);

    assertThat(balancePence(demoOperator, current)).isEqualTo(before);
  }

  @Test
  void demo_operator_can_adjust_a_demo_sandbox_account() {
    UUID current = sandbox.body().accountIds().getFirst();
    long before = balancePence(demoOperator, current);

    correct(demoOperator, goodwill(current, 5_00)).expectStatus().isEqualTo(HttpStatus.CREATED);

    assertThat(balancePence(demoOperator, current)).isEqualTo(before + 5_00);
  }

  @Test
  void demo_operator_reversing_a_transaction_outside_any_sandbox_is_forbidden() {
    AccountId seeded = ledger.customerAccountWithBalance(Money.ofPence(40_00));
    UUID funding = ledger.lastFundingOf(seeded).value();

    expectForbidden(correct(demoOperator, reversing(funding)));

    assertThat(balancePence(demoOperator, seeded.value())).isEqualTo(40_00);
  }

  @Test
  void demo_operator_adjusting_an_account_outside_any_sandbox_is_forbidden() {
    AccountId seeded = ledger.customerAccountWithBalance(Money.ofPence(40_00));

    expectForbidden(correct(demoOperator, goodwill(seeded.value(), 5_00)));

    assertThat(balancePence(demoOperator, seeded.value())).isEqualTo(40_00);
  }

  @Test
  void demo_operator_reversing_a_payment_from_a_sandbox_to_an_account_outside_it_is_forbidden() {
    AccountId seeded = ledger.customerAccountWithBalance(Money.ofPence(0));
    UUID current = sandbox.body().accountIds().getFirst();
    UUID payment = pay(current, seeded.value(), 10_00);

    expectForbidden(correct(demoOperator, reversing(payment)));

    assertThat(balancePence(demoOperator, seeded.value())).isEqualTo(10_00);
  }

  @Test
  void an_operator_who_logged_in_can_still_correct_any_transaction() {
    AccountId seeded = ledger.customerAccountWithBalance(Money.ofPence(40_00));
    UUID funding = ledger.lastFundingOf(seeded).value();

    correct(sessions.operator(), reversing(funding)).expectStatus().isEqualTo(HttpStatus.CREATED);
  }

  private UUID pay(UUID from, UUID to, long amountPence) {
    PaymentView payment =
        sandbox
            .client()
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": %d}
                """
                    .formatted(from, to, amountPence))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(PaymentView.class)
            .returnResult()
            .getResponseBody();
    assertThat(payment).isNotNull();
    return payment.transactionIds().getFirst();
  }

  private static RestTestClient.ResponseSpec correct(RestTestClient operator, String body) {
    return operator
        .post()
        .uri("/v1/corrections")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange();
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

  private static String reversing(UUID transaction) {
    return """
    {"kind": "REVERSING", "transactionId": "%s", "reason": "DUPLICATE_POSTING",
     "note": "Trying the demo"}
    """
        .formatted(transaction);
  }

  private static String goodwill(UUID account, long amountPence) {
    return """
    {"kind": "ADJUSTMENT", "reason": "GOODWILL", "note": "Trying the demo",
     "accountId": "%s", "amountPence": %d, "direction": "CREDIT"}
    """
        .formatted(account, amountPence);
  }

  record PaymentView(UUID id, List<UUID> transactionIds) {}
}
