package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.payments.EndToEndId;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Scenario 6, scheme resilience [M5]: lost, duplicated or contradictory Scheme Outcomes never lose
 * or double money, and unresolved Faster Payments are flagged as Stuck Payments, never reversed
 * automatically. With an in-memory fake PaymentScheme.
 *
 * <p>The dispatcher re-sends after 1 s instead of 15 s, flags a Stuck Payment after 4 s instead of
 * 5 min, and re-sends a Stuck Payment after 2 s instead of 1 min. The context has its own Postgres
 * and Kafka, closed after the class, so no other cached context's dispatcher claims these Payments
 * and the Stuck Payment gauge counts only this class's.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ledger.scheme.resend-after=PT1S",
      "ledger.scheme.stuck-after=PT4S",
      "ledger.scheme.stuck-resend-after=PT2S"
    })
@AutoConfigureRestTestClient
@Import({
  DedicatedContainersConfiguration.class,
  LedgerFixture.class,
  Sessions.class,
  FakePaymentScheme.class,
  SchemeCallbacks.class
})
@DirtiesContext
class SchemeResilienceTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired FakePaymentScheme scheme;
  @Autowired SchemeCallbacks callbacks;

  AccountId a;
  String payeeAccountNumber;
  RestTestClient customer;
  RestTestClient operator;

  @BeforeEach
  void givenAHas100Pounds() {
    a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    payeeAccountNumber = "%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
    customer = sessions.customerOwning(a);
    operator = sessions.operator();
  }

  @Test
  void scheme_submission_with_no_outcome_is_re_sent_with_the_same_end_to_end_id() {
    makeFasterPayment(40_00);

    List<FakePaymentScheme.Received> submissions =
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> scheme.submissionsTo(payeeAccountNumber), list -> list.size() >= 2);

    assertThat(submissions.stream().map(r -> r.submission().endToEndId()).distinct()).hasSize(1);
  }

  @Test
  void faster_payment_with_a_scheme_outcome_is_not_re_sent() throws InterruptedException {
    makeFasterPayment(40_00);
    EndToEndId endToEndId = scheme.awaitSubmissionTo(payeeAccountNumber).submission().endToEndId();

    callbacks.accept(endToEndId).expectStatus().isOk();

    Thread.sleep(2_500);
    assertThat(scheme.submissionsTo(payeeAccountNumber)).hasSize(1);
  }

  @Test
  void faster_payment_with_no_outcome_by_the_deadline_stays_pending_flagged_as_a_stuck_payment() {
    PaymentView pending = makeFasterPayment(40_00);
    assertThat(pending.stuck()).isFalse();

    await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> payment(pending.id()).stuck(), Boolean.TRUE::equals);

    PaymentView stuck = payment(pending.id());
    assertThat(stuck.status()).isEqualTo("PENDING");
    // Never reversed automatically: the money may already have left.
    assertThat(stuck.transactionIds()).isEqualTo(pending.transactionIds());
    assertThat(balancePenceOf(a)).isEqualTo(60_00);
    assertThat(SchemeGauges.stuckPayments(operator)).isGreaterThanOrEqualTo(1);
  }

  @Test
  void stuck_payment_is_re_sent_when_the_scheme_comes_back_and_then_completes() {
    scheme.goDownFor(payeeAccountNumber);
    PaymentView pending = makeFasterPayment(40_00);
    await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> payment(pending.id()).stuck(), Boolean.TRUE::equals);

    scheme.comeBackFor(payeeAccountNumber);
    EndToEndId endToEndId = scheme.awaitSubmissionTo(payeeAccountNumber).submission().endToEndId();
    callbacks.accept(endToEndId).expectStatus().isOk();

    PaymentView completed = payment(pending.id());
    assertThat(completed.status()).isEqualTo("COMPLETED");
    assertThat(completed.transactionIds()).isEqualTo(pending.transactionIds());
    assertThat(balancePenceOf(a)).isEqualTo(60_00);
  }

  @Test
  void stuck_payment_keeps_being_re_sent_with_the_same_end_to_end_id() {
    makeFasterPayment(40_00);

    List<FakePaymentScheme.Received> submissions =
        await()
            .atMost(Duration.ofSeconds(15))
            .until(() -> scheme.submissionsTo(payeeAccountNumber), list -> list.size() >= 6);

    assertThat(submissions.stream().map(r -> r.submission().endToEndId()).distinct()).hasSize(1);
  }

  @Test
  void duplicate_scheme_rejection_returns_200_and_posts_exactly_one_reversal() {
    PaymentView pending = makeFasterPayment(40_00);
    EndToEndId endToEndId = scheme.awaitSubmissionTo(payeeAccountNumber).submission().endToEndId();

    callbacks.reject(endToEndId, "AC04").expectStatus().isOk();
    callbacks.reject(endToEndId, "AC04").expectStatus().isOk();

    PaymentView rejected = payment(pending.id());
    assertThat(rejected.status()).isEqualTo("REJECTED");
    // The Payment Transaction and one Reversal.
    assertThat(rejected.transactionIds()).hasSize(2);
    assertThat(balancePenceOf(a)).isEqualTo(100_00);
  }

  @Test
  void scheme_rejection_after_acceptance_is_ignored_and_counted_as_a_scheme_anomaly() {
    PaymentView pending = makeFasterPayment(40_00);
    EndToEndId endToEndId = scheme.awaitSubmissionTo(payeeAccountNumber).submission().endToEndId();
    callbacks.accept(endToEndId).expectStatus().isOk();
    long anomaliesBefore = SchemeGauges.schemeAnomalies(operator);

    callbacks.reject(endToEndId, "AC04").expectStatus().isOk();

    PaymentView completed = payment(pending.id());
    assertThat(completed.status()).isEqualTo("COMPLETED");
    assertThat(completed.transactionIds()).isEqualTo(pending.transactionIds());
    assertThat(balancePenceOf(a)).isEqualTo(60_00);
    assertThat(SchemeGauges.schemeAnomalies(operator)).isEqualTo(anomaliesBefore + 1);
  }

  @Test
  void scheme_acceptance_after_rejection_is_ignored_and_counted_as_a_scheme_anomaly() {
    PaymentView pending = makeFasterPayment(40_00);
    EndToEndId endToEndId = scheme.awaitSubmissionTo(payeeAccountNumber).submission().endToEndId();
    callbacks.reject(endToEndId, "AC04").expectStatus().isOk();
    long anomaliesBefore = SchemeGauges.schemeAnomalies(operator);

    callbacks.accept(endToEndId).expectStatus().isOk();

    PaymentView rejected = payment(pending.id());
    assertThat(rejected.status()).isEqualTo("REJECTED");
    // The Payment Transaction and its one Reversal; acceptance posts nothing more.
    assertThat(rejected.transactionIds()).hasSize(2);
    assertThat(balancePenceOf(a)).isEqualTo(100_00);
    assertThat(SchemeGauges.schemeAnomalies(operator)).isEqualTo(anomaliesBefore + 1);
  }

  @Test
  void repeated_acceptance_is_not_a_scheme_anomaly() {
    makeFasterPayment(40_00);
    EndToEndId endToEndId = scheme.awaitSubmissionTo(payeeAccountNumber).submission().endToEndId();
    callbacks.accept(endToEndId).expectStatus().isOk();
    long anomaliesBefore = SchemeGauges.schemeAnomalies(operator);

    callbacks.accept(endToEndId).expectStatus().isOk();

    assertThat(SchemeGauges.schemeAnomalies(operator)).isEqualTo(anomaliesBefore);
  }

  @Test
  void scheme_outcome_for_an_unknown_end_to_end_id_is_refused_with_404() {
    callbacks
        .accept(EndToEndId.random())
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/not-found");
  }

  @Test
  void correction_of_a_transaction_whose_payment_is_pending_is_refused_as_payment_pending() {
    PaymentView pending = makeFasterPayment(40_00);

    reverse(pending.transactionIds().getFirst())
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/payment-pending");

    assertThat(payment(pending.id()).transactionIds()).hasSize(1);
    assertThat(balancePenceOf(a)).isEqualTo(60_00);
  }

  @Test
  void adjustment_naming_a_transaction_whose_payment_is_pending_is_refused_as_payment_pending() {
    PaymentView pending = makeFasterPayment(40_00);

    operator
        .post()
        .uri("/v1/corrections")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"kind": "ADJUSTMENT", "transactionId": "%s", "reason": "WRONG_AMOUNT",
             "note": "Charged 1.00 too much", "accountId": "%s", "amountPence": 100,
             "direction": "CREDIT"}
            """
                .formatted(pending.transactionIds().getFirst(), a.value()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/payment-pending");

    assertThat(balancePenceOf(a)).isEqualTo(60_00);
  }

  @Test
  void correction_of_a_transaction_whose_payment_has_completed_is_posted() {
    PaymentView pending = makeFasterPayment(40_00);
    EndToEndId endToEndId = scheme.awaitSubmissionTo(payeeAccountNumber).submission().endToEndId();
    callbacks.accept(endToEndId).expectStatus().isOk();

    reverse(pending.transactionIds().getFirst()).expectStatus().isEqualTo(HttpStatus.CREATED);

    assertThat(balancePenceOf(a)).isEqualTo(100_00);
  }

  RestTestClient.ResponseSpec reverse(UUID transactionId) {
    return operator
        .post()
        .uri("/v1/corrections")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"kind": "REVERSING", "transactionId": "%s", "reason": "OTHER",
             "note": "Customer says the payment never arrived"}
            """
                .formatted(transactionId))
        .exchange();
  }

  PaymentView makeFasterPayment(long amountPence) {
    PaymentView payment =
        customer
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"debtorAccountId": "%s",
                 "payee": {"sortCode": "040004", "accountNumber": "%s", "name": "Jane Doe"},
                 "amountPence": %d}
                """
                    .formatted(a.value(), payeeAccountNumber, amountPence))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(PaymentView.class)
            .returnResult()
            .getResponseBody();
    assertThat(payment).isNotNull();
    return payment;
  }

  PaymentView payment(UUID id) {
    PaymentView payment =
        customer
            .get()
            .uri("/v1/payments/{id}", id)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(PaymentView.class)
            .returnResult()
            .getResponseBody();
    assertThat(payment).isNotNull();
    return payment;
  }

  long balancePenceOf(AccountId account) {
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

  record PaymentView(UUID id, String status, Boolean stuck, List<UUID> transactionIds) {}

  record BalanceView(UUID accountId, long balancePence) {}
}
