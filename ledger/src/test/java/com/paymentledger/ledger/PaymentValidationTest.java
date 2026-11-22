package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
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

/** Scenario 1: invalid Payment requests are refused as problems and store nothing [M1]. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class, Sessions.class})
class PaymentValidationTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;

  AccountId a;
  AccountId b;
  RestTestClient customer;
  RestTestClient operator;

  @BeforeEach
  void givenAHas100PoundsAndBHasNothing() {
    a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    b = ledger.customerAccountWithBalance(Money.ofPence(0));
    customer = sessions.customerOwning(a);
    operator = sessions.operator();
  }

  @Test
  void rejects_a_zero_amount_as_validation_failed_and_posts_nothing() {
    expectProblem(
        transfer(a.value(), b.value(), 0), HttpStatus.BAD_REQUEST, "/problems/validation-failed");

    assertBalancesUnchanged();
  }

  @Test
  void rejects_a_negative_amount_as_validation_failed_and_posts_nothing() {
    expectProblem(
        transfer(a.value(), b.value(), -1), HttpStatus.BAD_REQUEST, "/problems/validation-failed");

    assertBalancesUnchanged();
  }

  @Test
  void rejects_an_amount_above_one_million_pounds_as_validation_failed_and_posts_nothing() {
    expectProblem(
        transfer(a.value(), b.value(), 1_000_000_01),
        HttpStatus.BAD_REQUEST,
        "/problems/validation-failed");

    assertBalancesUnchanged();
  }

  @Test
  void rejects_the_same_account_as_payer_and_payee_as_validation_failed_and_posts_nothing() {
    expectProblem(
        transfer(a.value(), a.value(), 10_00),
        HttpStatus.BAD_REQUEST,
        "/problems/validation-failed");

    assertBalancesUnchanged();
  }

  @Test
  void rejects_an_unknown_payee_account_as_not_found_and_posts_nothing() {
    expectProblem(
        transfer(a.value(), UUID.randomUUID(), 10_00), HttpStatus.NOT_FOUND, "/problems/not-found");

    assertBalancesUnchanged();
  }

  @Test
  void rejects_an_unknown_payer_account_as_not_found_and_posts_nothing() {
    expectProblem(
        transfer(UUID.randomUUID(), b.value(), 10_00), HttpStatus.NOT_FOUND, "/problems/not-found");

    assertBalancesUnchanged();
  }

  private void expectProblem(String body, HttpStatus status, String type) {
    customer
        .post()
        .uri("/v1/payments")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectStatus()
        .isEqualTo(status)
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectHeader()
        .doesNotExist("Location")
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo(type)
        .jsonPath("$.status")
        .isEqualTo(status.value())
        .jsonPath("$.detail")
        .isNotEmpty();
  }

  /** No Transaction was posted: both Balances are as arranged. */
  private void assertBalancesUnchanged() {
    assertThat(balancePenceOf(a)).isEqualTo(100_00);
    assertThat(balancePenceOf(b)).isZero();
  }

  private long balancePenceOf(AccountId account) {
    BalanceView balance =
        operator
            .get()
            .uri("/v1/accounts/{id}/balance", account.value())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(BalanceView.class)
            .returnResult()
            .getResponseBody();
    assertThat(balance).isNotNull();
    return balance.balancePence();
  }

  record BalanceView(UUID accountId, long balancePence) {}

  private static String transfer(UUID from, UUID to, long amountPence) {
    return """
    {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": %d}
    """
        .formatted(from, to, amountPence);
  }
}
