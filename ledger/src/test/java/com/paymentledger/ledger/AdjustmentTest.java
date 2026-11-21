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

  record BalanceView(UUID accountId, long balancePence) {}
}
