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
 * <p>The dispatcher re-sends after 1 s instead of 15 s, and flags a Stuck Payment after 4 s instead
 * of 5 min. The context has its own Postgres and Kafka, closed after the class, so no other cached
 * context's dispatcher claims these Payments and the Stuck Payment gauge counts only this class's.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"ledger.scheme.resend-after=PT1S", "ledger.scheme.stuck-after=PT4S"})
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
