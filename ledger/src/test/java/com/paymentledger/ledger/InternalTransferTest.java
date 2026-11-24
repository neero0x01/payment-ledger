package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Scenario 1: Internal Transfer [M1]. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class, Sessions.class})
class InternalTransferTest {

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
  void internal_transfer_returns_201_with_the_payment_completed() {
    customer
        .post()
        .uri("/v1/payments")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(transfer(a, b, 30_00))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectHeader()
        .exists("Location")
        .expectBody()
        .jsonPath("$.id")
        .isNotEmpty()
        .jsonPath("$.type")
        .isEqualTo("INTERNAL_TRANSFER")
        .jsonPath("$.status")
        .isEqualTo("COMPLETED")
        .jsonPath("$.amountPence")
        .isEqualTo(30_00);
  }

  @Test
  void internal_transfer_posts_exactly_one_transaction_debiting_payer_and_crediting_payee() {
    PaymentView payment = makeTransfer(a, b, 30_00);

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
    assertThat(transaction.kind()).isEqualTo("PAYMENT");
    assertThat(transaction.paymentId()).isEqualTo(payment.id());
    assertThat(transaction.source()).isEqualTo("LIVE");
    assertThat(transaction.entries())
        .containsExactlyInAnyOrder(
            new EntryView(a.value(), "DEBIT", 30_00), new EntryView(b.value(), "CREDIT", 30_00));
  }

  @Test
  void completed_internal_transfer_can_be_read_back_at_its_location() {
    String location =
        customer
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(transfer(a, b, 30_00))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .returnResult()
            .getResponseHeaders()
            .getFirst("Location");

    PaymentView payment =
        customer
            .get()
            .uri(location)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(PaymentView.class)
            .returnResult()
            .getResponseBody();

    assertThat(payment).isNotNull();
    assertThat(payment.status()).isEqualTo("COMPLETED");
    assertThat(payment.transactionIds()).hasSize(1);
  }

  @Test
  void internal_transfer_moves_30_pounds_from_payer_to_payee() {
    makeTransfer(a, b, 30_00);

    assertThat(balanceOf(a)).isEqualTo(new BalanceView(a.value(), 70_00));
    assertThat(balanceOf(b)).isEqualTo(new BalanceView(b.value(), 30_00));
  }

  /** Scenario 1 [M3]. */
  @Test
  void paying_from_another_customers_account_is_forbidden_and_stores_nothing() {
    RestTestClient ownerOfB = sessions.customerOwning(b);

    ownerOfB
        .post()
        .uri("/v1/payments")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(transfer(a, b, 30_00))
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectHeader()
        .doesNotExist("Location")
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/forbidden");

    assertThat(balanceOf(a)).isEqualTo(new BalanceView(a.value(), 100_00));
    assertThat(balanceOf(b)).isEqualTo(new BalanceView(b.value(), 0));
  }

  private BalanceView balanceOf(AccountId account) {
    return operator
        .get()
        .uri("/v1/accounts/{id}/balance", account.value())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(BalanceView.class)
        .returnResult()
        .getResponseBody();
  }

  record BalanceView(UUID accountId, long balancePence) {}

  private PaymentView makeTransfer(AccountId from, AccountId to, long amountPence) {
    PaymentView payment =
        customer
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(transfer(from, to, amountPence))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(PaymentView.class)
            .returnResult()
            .getResponseBody();
    assertThat(payment).isNotNull();
    return payment;
  }

  record PaymentView(UUID id, String status, List<UUID> transactionIds) {}

  record TransactionView(
      UUID id, String kind, UUID paymentId, String source, List<EntryView> entries) {}

  record EntryView(UUID accountId, String side, long amountPence) {}

  private static String transfer(AccountId from, AccountId to, long amountPence) {
    return """
    {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": %d}
    """
        .formatted(from.value(), to.value(), amountPence);
  }
}
