package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Scenario 4: Concurrent payments [M3]. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class, Sessions.class})
class ConcurrentPaymentsTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;

  RestTestClient operator;

  @BeforeEach
  void givenAnOperator() {
    operator = sessions.operator();
  }

  @RepeatedTest(50)
  void of_two_simultaneous_70_pound_payments_from_100_pounds_exactly_one_completes() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient customer = sessions.customerOwning(a);

    List<PaymentView> payments =
        atOnce(List.of(() -> pay(customer, a, b, 70_00), () -> pay(customer, a, b, 70_00)));

    assertThat(payments)
        .extracting(PaymentView::status, PaymentView::rejectionReason)
        .containsExactlyInAnyOrder(
            tuple("COMPLETED", null), tuple("REJECTED", "INSUFFICIENT_FUNDS"));
    assertThat(balancePenceOf(a)).isEqualTo(30_00);
    assertThat(balancePenceOf(b)).isEqualTo(70_00);
    PaymentView completed =
        payments.stream().filter(p -> p.status().equals("COMPLETED")).findFirst().orElseThrow();
    assertThat(transactionOf(completed).entries())
        .containsExactlyInAnyOrder(
            new EntryView(a.value(), "DEBIT", 70_00, 2, 30_00),
            new EntryView(b.value(), "CREDIT", 70_00, 1, 70_00));
  }

  @Test
  void of_twenty_simultaneous_10_pound_payments_from_100_pounds_exactly_ten_complete() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient customer = sessions.customerOwning(a);

    List<PaymentView> payments =
        atOnce(
            IntStream.range(0, 20).<Call>mapToObj(i -> () -> pay(customer, a, b, 10_00)).toList());

    List<PaymentView> completed =
        payments.stream().filter(p -> p.status().equals("COMPLETED")).toList();
    assertThat(completed).hasSize(10);
    assertThat(payments.stream().filter(p -> p.status().equals("REJECTED")))
        .hasSize(10)
        .allMatch(p -> "INSUFFICIENT_FUNDS".equals(p.rejectionReason()));
    assertThat(balancePenceOf(a)).isZero();
    assertThat(balancePenceOf(b)).isEqualTo(100_00);
    // A's Entries after its funding are numbered 2 to 11, each £10.00 lower than the one before.
    List<EntryView> debits =
        completed.stream()
            .flatMap(p -> transactionOf(p).entries().stream())
            .filter(e -> e.accountId().equals(a.value()))
            .toList();
    assertThat(debits)
        .extracting(EntryView::entrySeq, EntryView::balanceAfterPence)
        .containsExactlyInAnyOrderElementsOf(
            IntStream.rangeClosed(2, 11)
                .mapToObj(seq -> tuple((long) seq, 100_00L - (seq - 1) * 10_00L))
                .toList());
  }

  @RepeatedTest(100)
  void payments_from_a_to_b_and_from_b_to_a_at_once_never_deadlock() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    RestTestClient ownerOfA = sessions.customerOwning(a);
    RestTestClient ownerOfB = sessions.customerOwning(b);

    List<PaymentView> payments =
        atOnce(List.of(() -> pay(ownerOfA, a, b, 1_00), () -> pay(ownerOfB, b, a, 1_00)));

    assertThat(payments).extracting(PaymentView::status).containsOnly("COMPLETED");
    assertThat(balancePenceOf(a)).isEqualTo(100_00);
    assertThat(balancePenceOf(b)).isEqualTo(100_00);
  }

  @FunctionalInterface
  interface Call {
    PaymentView make();
  }

  /** Releases the calls at the same moment, by a CyclicBarrier, and waits for them all. */
  private static List<PaymentView> atOnce(List<Call> calls) {
    CyclicBarrier barrier = new CyclicBarrier(calls.size());
    try (ExecutorService threads = Executors.newFixedThreadPool(calls.size())) {
      List<Future<PaymentView>> results =
          calls.stream()
              .map(call ->
                  threads.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return call.make();
                  }))
              .toList();
      return results.stream().map(ConcurrentPaymentsTest::outcome).toList();
    }
  }

  private static PaymentView outcome(Future<PaymentView> result) {
    try {
      return result.get(60, TimeUnit.SECONDS);
    } catch (Exception failed) {
      throw new AssertionError("A concurrent Payment failed", failed);
    }
  }

  private static PaymentView pay(
      RestTestClient customer, AccountId from, AccountId to, long amountPence) {
    PaymentView payment =
        customer
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
    return payment;
  }

  private TransactionView transactionOf(PaymentView payment) {
    assertThat(payment.transactionIds()).hasSize(1);
    TransactionView transaction =
        operator
            .get()
            .uri("/v1/transactions/{id}", payment.transactionIds().getFirst())
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

  record PaymentView(UUID id, String status, String rejectionReason, List<UUID> transactionIds) {}

  record TransactionView(UUID id, List<EntryView> entries) {}

  record EntryView(
      UUID accountId, String side, long amountPence, long entrySeq, long balanceAfterPence) {}

  record BalanceView(UUID accountId, long balancePence) {}
}
