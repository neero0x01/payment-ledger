package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.payments.Payee;
import com.paymentledger.ledger.payments.SchemeSubmission;
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
import org.springframework.test.web.servlet.client.RestTestClient;

/** Scenario 6: Outbound Faster Payment [M5], with an in-memory fake PaymentScheme. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({
  TestcontainersConfiguration.class,
  LedgerFixture.class,
  Sessions.class,
  FakePaymentScheme.class
})
class FasterPaymentTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired FakePaymentScheme scheme;

  AccountId a;
  String payeeAccountNumber;
  RestTestClient customer;
  RestTestClient operator;

  @BeforeEach
  void givenAHas100Pounds() {
    a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    payeeAccountNumber = randomAccountNumber();
    customer = sessions.customerOwning(a);
    operator = sessions.operator();
  }

  @Test
  void faster_payment_returns_201_with_the_payment_pending() {
    customer
        .post()
        .uri("/v1/payments")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(fasterPayment(a, payeeAccountNumber, 40_00))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectHeader()
        .exists("Location")
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("FASTER_PAYMENT")
        .jsonPath("$.status")
        .isEqualTo("PENDING")
        .jsonPath("$.payee.sortCode")
        .isEqualTo("040004")
        .jsonPath("$.payee.accountNumber")
        .isEqualTo(payeeAccountNumber)
        .jsonPath("$.payee.name")
        .isEqualTo("Jane Doe")
        .jsonPath("$.amountPence")
        .isEqualTo(40_00);
  }

  @Test
  void faster_payment_posts_one_transaction_debiting_payer_and_crediting_fps_clearing() {
    long fpsClearingBefore = balancePenceOf(ledger.fpsClearing());

    PaymentView payment = makeFasterPayment(40_00);

    assertThat(payment.transactionIds()).hasSize(1);
    TransactionView transaction = transaction(payment.transactionIds().getFirst());
    assertThat(transaction.kind()).isEqualTo("PAYMENT");
    assertThat(transaction.paymentId()).isEqualTo(payment.id());
    assertThat(transaction.entries())
        .containsExactlyInAnyOrder(
            new EntryView(a.value(), "DEBIT", 40_00),
            new EntryView(ledger.fpsClearing().value(), "CREDIT", 40_00));
    assertThat(balancePenceOf(a)).isEqualTo(60_00);
    // FPS Clearing is debit-normal, so a Credit takes it down.
    assertThat(balancePenceOf(ledger.fpsClearing())).isEqualTo(fpsClearingBefore - 40_00);
  }

  @Test
  void scheme_submission_with_a_new_end_to_end_id_goes_through_the_payment_scheme_after_commit() {
    makeFasterPayment(40_00);

    FakePaymentScheme.Received received = scheme.awaitSubmissionTo(payeeAccountNumber);

    SchemeSubmission submission = received.submission();
    assertThat(submission.endToEndId().value()).isNotBlank();
    assertThat(submission.debtorAccount()).isEqualTo(a);
    assertThat(submission.creditor())
        .isEqualTo(new Payee.AtAnotherBank("040004", payeeAccountNumber, "Jane Doe"));
    assertThat(submission.amount()).isEqualTo(Money.ofPence(40_00));
    // The Payment's Transaction had committed when the scheme was called.
    assertThat(received.debtorBalance()).isEqualTo(Money.ofPence(60_00));
  }

  @Test
  void each_faster_payment_is_submitted_once_with_its_own_end_to_end_id()
      throws InterruptedException {
    makeFasterPayment(10_00);
    makeFasterPayment(20_00);

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> scheme.submissionsTo(payeeAccountNumber), list -> list.size() >= 2);
    // A later poll of the dispatcher must not send either again.
    Thread.sleep(1_000);
    List<FakePaymentScheme.Received> submissions = scheme.submissionsTo(payeeAccountNumber);
    assertThat(submissions).hasSize(2);
    assertThat(submissions.stream().map(r -> r.submission().endToEndId()).distinct()).hasSize(2);
  }

  @Test
  void faster_payment_to_a_malformed_sort_code_is_refused_with_400_and_stores_nothing() {
    customer
        .post()
        .uri("/v1/payments")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"debtorAccountId": "%s",
             "payee": {"sortCode": "04-00", "accountNumber": "%s", "name": "Jane Doe"},
             "amountPence": 40000}
            """
                .formatted(a.value(), payeeAccountNumber))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/validation-failed");

    assertThat(balancePenceOf(a)).isEqualTo(100_00);
  }

  PaymentView makeFasterPayment(long amountPence) {
    PaymentView payment =
        customer
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(fasterPayment(a, payeeAccountNumber, amountPence))
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

  TransactionView transaction(UUID id) {
    TransactionView transaction =
        operator
            .get()
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

  record PaymentView(
      UUID id,
      String type,
      String status,
      String rejectionReason,
      String schemeReasonCode,
      List<UUID> transactionIds) {}

  record TransactionView(
      UUID id, String kind, UUID paymentId, UUID reversesTransactionId, List<EntryView> entries) {}

  record EntryView(UUID accountId, String side, long amountPence) {}

  record BalanceView(UUID accountId, long balancePence) {}

  /** A random account number at another bank, so each test can find its own Scheme Submission. */
  static String randomAccountNumber() {
    return "%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
  }

  static String fasterPayment(AccountId from, String accountNumber, long amountPence) {
    return """
    {"debtorAccountId": "%s",
     "payee": {"sortCode": "040004", "accountNumber": "%s", "name": "Jane Doe"},
     "amountPence": %d}
    """
        .formatted(from.value(), accountNumber, amountPence);
  }
}
