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

/** Scenario 7: Immutable Corrections, the Reversing Correction [M2]. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class})
class ReversingCorrectionTest {

  @Autowired RestTestClient http;
  @Autowired LedgerFixture ledger;

  AccountId a;
  AccountId b;
  UUID mistake;

  @BeforeEach
  void givenAPaidB30PoundsByMistake() {
    a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    b = ledger.customerAccountWithBalance(Money.ofPence(0));
    mistake = payAndReturnTransaction(a, b, 30_00);
  }

  @Test
  void reversing_correction_posts_a_transaction_mirroring_the_original_with_the_sides_swapped() {
    TransactionView original = transaction(mistake);

    TransactionView correction = postReversingCorrection(mistake);

    assertThat(correction.kind()).isEqualTo("CORRECTION");
    assertThat(correction.id()).isNotEqualTo(mistake);
    assertThat(correction.entries())
        .containsExactlyInAnyOrder(
            new EntryView(a.value(), "CREDIT", 30_00), new EntryView(b.value(), "DEBIT", 30_00));
    assertThat(transaction(correction.id())).isEqualTo(correction);
    assertThat(original.entries())
        .containsExactlyInAnyOrder(
            new EntryView(a.value(), "DEBIT", 30_00), new EntryView(b.value(), "CREDIT", 30_00));
  }

  @Test
  void reversing_correction_is_linked_to_the_transaction_it_corrects_which_is_unchanged() {
    TransactionView before = transaction(mistake);

    TransactionView correction = postReversingCorrection(mistake);

    assertThat(correction.reversesTransactionId()).isEqualTo(mistake);
    assertThat(transaction(mistake)).isEqualTo(before);
  }

  @Test
  void reversing_correction_returns_balances_to_what_they_were_before_the_original() {
    postReversingCorrection(mistake);

    assertThat(balancePenceOf(a)).isEqualTo(100_00);
    assertThat(balancePenceOf(b)).isZero();
  }

  private TransactionView postReversingCorrection(UUID transactionId) {
    TransactionView correction =
        http.post()
            .uri("/v1/corrections")
            .contentType(MediaType.APPLICATION_JSON)
            .body(reversing(transactionId))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectHeader()
            .exists("Location")
            .expectBody(TransactionView.class)
            .returnResult()
            .getResponseBody();
    assertThat(correction).isNotNull();
    return correction;
  }

  private static String reversing(UUID transactionId) {
    return """
    {"kind": "REVERSING", "transactionId": "%s"}
    """
        .formatted(transactionId);
  }

  private UUID payAndReturnTransaction(AccountId from, AccountId to, long amountPence) {
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
    return payment.transactionIds().getFirst();
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

  record PaymentView(UUID id, List<UUID> transactionIds) {}

  record TransactionView(
      UUID id,
      String kind,
      Instant bookingTime,
      @Nullable UUID reversesTransactionId,
      List<EntryView> entries) {}

  record EntryView(UUID accountId, String side, long amountPence) {}

  record BalanceView(UUID accountId, long balancePence) {}
}
