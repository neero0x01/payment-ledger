package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Scenario 7: Immutable Corrections, the Adjustment [M2]. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class})
class AdjustmentTest {

  @Autowired RestTestClient http;
  @Autowired LedgerFixture ledger;

  AccountId customer;
  AccountId corrections;

  @BeforeEach
  void givenACustomerAccountWith20Pounds() {
    customer = ledger.customerAccountWithBalance(Money.ofPence(20_00));
    corrections = ledger.corrections();
  }

  @Test
  void goodwill_adjustment_debits_the_corrections_account_and_credits_the_customer_account() {
    long correctionsBefore = balancePenceOf(corrections);

    TransactionView adjustment =
        postAdjustment(
            """
            {"kind": "ADJUSTMENT", "reason": "GOODWILL", "note": "Sorry for the delay",
             "accountId": "%s", "amountPence": 500, "direction": "CREDIT"}
            """
                .formatted(customer.value()));

    assertThat(adjustment.kind()).isEqualTo("CORRECTION");
    assertThat(adjustment.correctionReason()).isEqualTo("GOODWILL");
    assertThat(adjustment.correctionNote()).isEqualTo("Sorry for the delay");
    assertThat(adjustment.entries())
        .containsExactlyInAnyOrder(
            new EntryView(corrections.value(), "DEBIT", 5_00),
            new EntryView(customer.value(), "CREDIT", 5_00));
    assertThat(transaction(adjustment.id())).isEqualTo(adjustment);
    assertThat(balancePenceOf(customer)).isEqualTo(25_00);
    assertThat(balancePenceOf(corrections)).isEqualTo(correctionsBefore + 5_00);
  }

  @Test
  void debit_adjustment_credits_the_corrections_account_and_debits_the_customer_account() {
    TransactionView adjustment =
        postAdjustment(
            """
            {"kind": "ADJUSTMENT", "reason": "WRONG_AMOUNT", "note": "Refund paid twice",
             "accountId": "%s", "amountPence": 300, "direction": "DEBIT"}
            """
                .formatted(customer.value()));

    assertThat(adjustment.entries())
        .containsExactlyInAnyOrder(
            new EntryView(corrections.value(), "CREDIT", 3_00),
            new EntryView(customer.value(), "DEBIT", 3_00));
    assertThat(balancePenceOf(customer)).isEqualTo(17_00);
  }

  @Test
  void adjustment_without_a_correction_reason_is_refused_as_validation_failed_and_posts_nothing() {
    expectValidationFailed(
        """
        {"kind": "ADJUSTMENT", "note": "Sorry for the delay",
         "accountId": "%s", "amountPence": 500, "direction": "CREDIT"}
        """
            .formatted(customer.value()));

    assertThat(balancePenceOf(customer)).isEqualTo(20_00);
  }

  @Test
  void adjustment_without_a_note_is_refused_as_validation_failed_and_posts_nothing() {
    expectValidationFailed(
        """
        {"kind": "ADJUSTMENT", "reason": "GOODWILL",
         "accountId": "%s", "amountPence": 500, "direction": "CREDIT"}
        """
            .formatted(customer.value()));

    assertThat(balancePenceOf(customer)).isEqualTo(20_00);
  }

  @Test
  void adjustment_with_a_blank_note_is_refused_as_validation_failed_and_posts_nothing() {
    expectValidationFailed(
        """
        {"kind": "ADJUSTMENT", "reason": "GOODWILL", "note": "  ",
         "accountId": "%s", "amountPence": 500, "direction": "CREDIT"}
        """
            .formatted(customer.value()));

    assertThat(balancePenceOf(customer)).isEqualTo(20_00);
  }

  @Test
  void adjustment_of_a_zero_amount_is_refused_as_validation_failed_and_posts_nothing() {
    expectValidationFailed(
        """
        {"kind": "ADJUSTMENT", "reason": "GOODWILL", "note": "Sorry for the delay",
         "accountId": "%s", "amountPence": 0, "direction": "CREDIT"}
        """
            .formatted(customer.value()));

    assertThat(balancePenceOf(customer)).isEqualTo(20_00);
  }

  @Test
  void adjustment_without_an_account_amount_or_direction_is_refused_as_validation_failed() {
    expectValidationFailed(
        """
        {"kind": "ADJUSTMENT", "reason": "GOODWILL", "note": "Sorry for the delay",
         "amountPence": 500, "direction": "CREDIT"}
        """);
    expectValidationFailed(
        """
        {"kind": "ADJUSTMENT", "reason": "GOODWILL", "note": "Sorry for the delay",
         "accountId": "%s", "direction": "CREDIT"}
        """
            .formatted(customer.value()));
    expectValidationFailed(
        """
        {"kind": "ADJUSTMENT", "reason": "GOODWILL", "note": "Sorry for the delay",
         "accountId": "%s", "amountPence": 500}
        """
            .formatted(customer.value()));

    assertThat(balancePenceOf(customer)).isEqualTo(20_00);
  }

  @Test
  void adjustment_of_an_internal_account_is_refused_as_validation_failed() {
    expectValidationFailed(adjustment(ledger.funding(), "OTHER", 5_00, "CREDIT"));
  }

  @Test
  void adjustment_of_an_unknown_account_is_refused_as_not_found() {
    http.post()
        .uri("/v1/corrections")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"kind": "ADJUSTMENT", "reason": "GOODWILL", "note": "Sorry for the delay",
             "accountId": "%s", "amountPence": 500, "direction": "CREDIT"}
            """
                .formatted(UUID.randomUUID()))
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/not-found");
  }

  @Test
  void correction_skips_the_funds_check_and_after_it_outbound_payments_are_rejected() {
    AccountId overdrawn =
        ledger.customerAccountWithBalance(Money.ofPence(20_00), Money.ofPence(10_00));
    AccountId payee = ledger.customerAccountWithBalance(Money.ofPence(0));

    postAdjustment(adjustment(overdrawn, "WRONG_AMOUNT", 50_00, "DEBIT"));

    assertThat(balancePenceOf(overdrawn)).isEqualTo(-30_00);
    PaymentView payment = makePayment(overdrawn, payee, 1);
    assertThat(payment.status()).isEqualTo("REJECTED");
    assertThat(payment.rejectionReason()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(balancePenceOf(overdrawn)).isEqualTo(-30_00);
  }

  @Test
  void account_pushed_past_its_overdraft_limit_can_pay_out_again_once_funded() {
    AccountId overdrawn =
        ledger.customerAccountWithBalance(Money.ofPence(20_00), Money.ofPence(10_00));
    AccountId payee = ledger.customerAccountWithBalance(Money.ofPence(0));
    postAdjustment(adjustment(overdrawn, "WRONG_AMOUNT", 50_00, "DEBIT"));

    postAdjustment(adjustment(overdrawn, "GOODWILL", 25_00, "CREDIT"));

    assertThat(makePayment(overdrawn, payee, 5_00).status()).isEqualTo("COMPLETED");
    assertThat(balancePenceOf(overdrawn)).isEqualTo(-10_00);
  }

  private static String adjustment(
      AccountId account, String reason, long amountPence, String direction) {
    return """
    {"kind": "ADJUSTMENT", "reason": "%s", "note": "Operator note",
     "accountId": "%s", "amountPence": %d, "direction": "%s"}
    """
        .formatted(reason, account.value(), amountPence, direction);
  }

  private PaymentView makePayment(AccountId from, AccountId to, long amountPence) {
    PaymentView payment =
        http.post()
            .uri("/v1/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": %d}
                """
                    .formatted(from.value(), to.value(), amountPence))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(PaymentView.class)
            .returnResult()
            .getResponseBody();
    assertThat(payment).isNotNull();
    return payment;
  }

  private void expectValidationFailed(String body) {
    http.post()
        .uri("/v1/corrections")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST)
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectHeader()
        .doesNotExist("Location")
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/validation-failed")
        .jsonPath("$.detail")
        .isNotEmpty();
  }

  private TransactionView postAdjustment(String body) {
    TransactionView adjustment =
        http.post()
            .uri("/v1/corrections")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectHeader()
            .exists("Location")
            .expectBody(TransactionView.class)
            .returnResult()
            .getResponseBody();
    assertThat(adjustment).isNotNull();
    return adjustment;
  }

  private TransactionView transaction(UUID id) {
    TransactionView transaction =
        http.get()
            .uri("/v1/transactions/{id}", id)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(TransactionView.class)
            .returnResult()
            .getResponseBody();
    assertThat(transaction).isNotNull();
    return transaction;
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

  record TransactionView(
      UUID id,
      String kind,
      Instant bookingTime,
      @Nullable String correctionReason,
      @Nullable String correctionNote,
      List<EntryView> entries) {}

  record EntryView(UUID accountId, String side, long amountPence) {}

  record PaymentView(UUID id, String status, @Nullable String rejectionReason) {}

  record BalanceView(UUID accountId, long balancePence) {}
}
