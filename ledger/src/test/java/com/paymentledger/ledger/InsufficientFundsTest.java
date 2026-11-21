package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.util.List;
import java.util.UUID;
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
@Import({TestcontainersConfiguration.class, LedgerFixture.class})
class InsufficientFundsTest {

  @Autowired RestTestClient http;
  @Autowired LedgerFixture ledger;

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

    String location =
        http.post()
            .uri("/v1/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .body(payment(a, b, 50_01))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .returnResult()
            .getResponseHeaders()
            .getFirst("Location");

    PaymentView stored =
        http.get()
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

  @Test
  void internal_account_has_no_funds_check_and_pays_beyond_its_balance() {
    AccountId funding = ledger.funding();
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));
    long beyondFundingBalance = balancePenceOf(funding) + 1_00;
    assumeThat(beyondFundingBalance).isLessThanOrEqualTo(1_000_000_00);

    PaymentView payment = makePayment(funding, b, beyondFundingBalance);

    assertThat(payment.status()).isEqualTo("COMPLETED");
    assertThat(balancePenceOf(b)).isEqualTo(beyondFundingBalance);
  }

  private PaymentView makePayment(AccountId from, AccountId to, long amountPence) {
    PaymentView payment =
        http.post()
            .uri("/v1/payments")
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
        http.get()
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
