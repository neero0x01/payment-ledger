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

/** Scenario 7 [M3]: Correction requests are safe to retry, as Payments are in Scenario 2. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class, Sessions.class})
class IdempotentCorrectionTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;

  RestTestClient operator;

  AccountId a;
  AccountId b;

  @BeforeEach
  void givenAHas100PoundsAndBHasNothing() {
    a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    b = ledger.customerAccountWithBalance(Money.ofPence(0));
    operator = sessions.operator();
  }

  @Test
  void a_correction_without_an_idempotency_key_is_refused_with_400_and_posts_nothing() {
    operator
        .post()
        .uri("/v1/corrections")
        .contentType(MediaType.APPLICATION_JSON)
        .body(goodwill(a, 5_00))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectHeader()
        .doesNotExist("Location")
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/idempotency-key-missing");

    assertThat(balancePenceOf(a)).isEqualTo(100_00);
  }

  @Test
  void the_same_key_and_body_again_replays_the_adjustment_with_200_and_posts_nothing_new() {
    String key = UUID.randomUUID().toString();
    TransactionView first = postCorrection(operator, key, goodwill(a, 5_00), HttpStatus.CREATED);

    TransactionView replay =
        operator
            .post()
            .uri("/v1/corrections")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .body(goodwill(a, 5_00))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals("Idempotent-Replayed", "true")
            .expectBody(TransactionView.class)
            .returnResult()
            .getResponseBody();

    assertThat(replay).isEqualTo(first);
    assertThat(balancePenceOf(a)).isEqualTo(105_00);
  }

  @Test
  void the_same_key_and_body_again_replays_the_reversing_correction_instead_of_refusing_it() {
    UUID mistake = payAndReturnTransaction(a, b, 30_00);
    String key = UUID.randomUUID().toString();
    TransactionView first = postCorrection(operator, key, reversing(mistake), HttpStatus.CREATED);

    TransactionView replay = postCorrection(operator, key, reversing(mistake), HttpStatus.OK);

    assertThat(replay).isEqualTo(first);
    assertThat(replay.reversesTransactionId()).isEqualTo(mistake);
    assertThat(balancePenceOf(a)).isEqualTo(100_00);
    assertThat(balancePenceOf(b)).isZero();
  }

  @Test
  void the_same_key_with_a_different_body_is_refused_with_422_and_posts_nothing() {
    String key = UUID.randomUUID().toString();
    postCorrection(operator, key, goodwill(a, 5_00), HttpStatus.CREATED);

    expectKeyReused(key, goodwill(a, 6_00));
    expectKeyReused(key, goodwill(b, 5_00));
    expectKeyReused(key, reversing(payAndReturnTransaction(a, b, 30_00)));

    assertThat(balancePenceOf(a)).isEqualTo(75_00);
    assertThat(balancePenceOf(b)).isEqualTo(30_00);
  }

  private void expectKeyReused(String key, String body) {
    operator
        .post()
        .uri("/v1/corrections")
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/idempotency-key-reused");
  }

  private static TransactionView postCorrection(
      RestTestClient client, String key, String body, HttpStatus expected) {
    TransactionView correction =
        client
            .post()
            .uri("/v1/corrections")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()
            .expectStatus()
            .isEqualTo(expected)
            .expectBody(TransactionView.class)
            .returnResult()
            .getResponseBody();
    assertThat(correction).isNotNull();
    return correction;
  }

  private UUID payAndReturnTransaction(AccountId from, AccountId to, long amountPence) {
    PaymentView payment =
        sessions
            .customerOwning(from)
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", UUID.randomUUID().toString())
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

  record PaymentView(UUID id, List<UUID> transactionIds) {}

  record TransactionView(
      UUID id,
      String kind,
      Instant bookingTime,
      @Nullable UUID reversesTransactionId,
      @Nullable String correctionReason,
      @Nullable String correctionNote,
      List<EntryView> entries) {}

  record EntryView(UUID accountId, String side, long amountPence) {}

  private static String reversing(UUID transactionId) {
    return """
    {"kind": "REVERSING", "transactionId": "%s", "reason": "DUPLICATE_POSTING",
     "note": "Paid twice by mistake"}
    """
        .formatted(transactionId);
  }

  private static String goodwill(AccountId account, long amountPence) {
    return """
    {"kind": "ADJUSTMENT", "reason": "GOODWILL", "note": "Sorry for the delay",
     "accountId": "%s", "amountPence": %d, "direction": "CREDIT"}
    """
        .formatted(account.value(), amountPence);
  }
}
