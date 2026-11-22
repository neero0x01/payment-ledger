package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Scenario 2: Idempotent retry [M3]. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class, Sessions.class})
class IdempotentPaymentTest {

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
  void a_payment_without_an_idempotency_key_is_refused_with_400_and_posts_nothing() {
    customer
        .post()
        .uri("/v1/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .body(transfer(a, b, 30_00))
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
    assertThat(balancePenceOf(b)).isZero();
  }

  @Test
  void the_same_key_and_body_again_replays_the_payment_with_200_and_posts_nothing_new() {
    String key = UUID.randomUUID().toString();
    PaymentView first = makePayment(customer, key, transfer(a, b, 30_00), HttpStatus.CREATED);

    PaymentView replay =
        customer
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .body(transfer(a, b, 30_00))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals("Idempotent-Replayed", "true")
            .expectBody(PaymentView.class)
            .returnResult()
            .getResponseBody();

    assertThat(replay).isEqualTo(first);
    assertThat(replay.status()).isEqualTo("COMPLETED");
    assertThat(replay.transactionIds()).hasSize(1);
    assertThat(balancePenceOf(a)).isEqualTo(70_00);
    assertThat(balancePenceOf(b)).isEqualTo(30_00);
  }

  @Test
  void the_same_key_with_a_different_body_is_refused_with_422_and_posts_nothing() {
    String key = UUID.randomUUID().toString();
    makePayment(customer, key, transfer(a, b, 30_00), HttpStatus.CREATED);

    customer
        .post()
        .uri("/v1/payments")
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .body(transfer(a, b, 40_00))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/idempotency-key-reused");

    assertThat(balancePenceOf(a)).isEqualTo(70_00);
    assertThat(balancePenceOf(b)).isEqualTo(30_00);
  }

  @Test
  void simultaneous_identical_requests_make_exactly_one_payment_that_every_response_describes()
      throws Exception {
    int rounds = 10;
    int simultaneous = 4;
    for (int round = 0; round < rounds; round++) {
      String key = UUID.randomUUID().toString();
      CyclicBarrier together = new CyclicBarrier(simultaneous);
      List<Callable<EntityExchangeResult<PaymentIdView>>> requests = new ArrayList<>();
      for (int i = 0; i < simultaneous; i++) {
        requests.add(() -> {
          together.await();
          return customer
              .post()
              .uri("/v1/payments")
              .header("Idempotency-Key", key)
              .contentType(MediaType.APPLICATION_JSON)
              .body(transfer(a, b, 1_00))
              .exchange()
              .expectBody(PaymentIdView.class)
              .returnResult();
        });
      }
      List<EntityExchangeResult<PaymentIdView>> responses = new ArrayList<>();
      try (ExecutorService threads = Executors.newFixedThreadPool(simultaneous)) {
        for (Future<EntityExchangeResult<PaymentIdView>> response : threads.invokeAll(requests)) {
          responses.add(response.get());
        }
      }

      assertThat(responses)
          .extracting(response -> response.getStatus().value())
          .containsExactlyInAnyOrder(201, 200, 200, 200);
      assertThat(responses)
          .extracting(response -> response.getResponseBody().id())
          .containsOnly(responses.getFirst().getResponseBody().id());
    }
    assertThat(balancePenceOf(a)).isEqualTo(100_00 - rounds * 1_00);
    assertThat(balancePenceOf(b)).isEqualTo(rounds * 1_00);
  }

  private static PaymentView makePayment(
      RestTestClient client, String key, String body, HttpStatus expected) {
    PaymentView payment =
        client
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()
            .expectStatus()
            .isEqualTo(expected)
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

  record BalanceView(UUID accountId, long balancePence) {}

  /** Just the Payment's id, which any response that describes a Payment has. */
  record PaymentIdView(UUID id) {}

  record PaymentView(
      UUID id,
      String status,
      String rejectionReason,
      long amountPence,
      List<UUID> transactionIds) {}

  private static String transfer(AccountId from, AccountId to, long amountPence) {
    return """
    {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": %d}
    """
        .formatted(from.value(), to.value(), amountPence);
  }
}
