package com.paymentledger.ledger;

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

/** Scenario 3: Insufficient funds and overdraft [M2]. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class, Sessions.class})
class InsufficientFundsTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;

  RestTestClient operator;

  @BeforeEach
  void givenAnOperator() {
    operator = sessions.operator();
  }

  @Test
  void completes_a_payment_within_the_overdraft_limit_taking_the_payer_below_zero() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(50_00), Money.ofPence(100_00));
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));

    PaymentView payment = makePayment(a, b, 150_00);

    assertThat(payment.status()).isEqualTo("COMPLETED");
    assertThat(payment.rejectionReason()).isNull();
    // £50.00 − £150.00. Spec Scenario 3 and #6 say −£50.00, which the arithmetic rules out.
    assertThat(balancePenceOf(a)).isEqualTo(-100_00);
    assertThat(balancePenceOf(b)).isEqualTo(150_00);
  }

  @Test
  void rejects_a_payment_one_penny_beyond_balance_plus_overdraft_limit_and_posts_nothing() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(50_00), Money.ofPence(100_00));
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));

    PaymentView payment = makePayment(a, b, 150_01);

    assertThat(payment.status()).isEqualTo("REJECTED");
    assertThat(payment.rejectionReason()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(payment.transactionIds()).isEmpty();
    assertThat(balancePenceOf(a)).isEqualTo(50_00);
    assertThat(balancePenceOf(b)).isZero();
  }

  @Test
  void rejects_payment_beyond_available_balance_with_insufficient_funds_and_posts_nothing() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(50_00));
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));

    PaymentView payment = makePayment(a, b, 50_01);

    assertThat(payment.status()).isEqualTo("REJECTED");
    assertThat(payment.rejectionReason()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(payment.transactionIds()).isEmpty();
    assertThat(balancePenceOf(a)).isEqualTo(50_00);
    assertThat(balancePenceOf(b)).isZero();
  }

  @Test
  void rejected_payment_is_stored_and_can_be_read_back_at_its_location() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(50_00));
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient customer = sessions.customerOwning(a);

    String location =
        customer
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(payment(a, b, 50_01))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .returnResult()
            .getResponseHeaders()
            .getFirst("Location");

    PaymentView stored =
        customer
            .get()
            .uri(location)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(PaymentView.class)
            .returnResult()
            .getResponseBody();

    assertThat(stored).isNotNull();
    assertThat(stored.status()).isEqualTo("REJECTED");
    assertThat(stored.rejectionReason()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(stored.amountPence()).isEqualTo(50_01);
    assertThat(stored.transactionIds()).isEmpty();
  }

  /**
   * No one may make a Payment from an Internal Account (spec §8), so a Correction shows it: an
   * Adjustment that debits a Customer Account credits the debit-normal Corrections Account.
   */
  @Test
  void internal_account_has_no_funds_check_and_goes_below_zero() {
    AccountId corrections = ledger.corrections();
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));
    long beyondCorrectionsBalance = Math.max(balancePenceOf(corrections), 0) + 1_00;

    operator
        .post()
        .uri("/v1/corrections")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"kind": "ADJUSTMENT", "reason": "OTHER", "note": "Operator note",
             "accountId": "%s", "amountPence": %d, "direction": "DEBIT"}
            """
                .formatted(b.value(), beyondCorrectionsBalance))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);

    assertThat(balancePenceOf(corrections)).isNegative();
  }

  private PaymentView makePayment(AccountId from, AccountId to, long amountPence) {
    PaymentView payment =
        sessions
            .customerOwning(from)
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(payment(from, to, amountPence))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectHeader()
            .exists("Location")
            .expectBody(PaymentView.class)
            .returnResult()
            .getResponseBody();
    assertThat(payment).isNotNull();
    return payment;
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

  record PaymentView(
      UUID id,
      String status,
      String rejectionReason,
      long amountPence,
      List<UUID> transactionIds) {}

  record BalanceView(UUID accountId, long balancePence) {}

  private static String payment(AccountId from, AccountId to, long amountPence) {
    return """
    {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": %d}
    """
        .formatted(from.value(), to.value(), amountPence);
  }
}
