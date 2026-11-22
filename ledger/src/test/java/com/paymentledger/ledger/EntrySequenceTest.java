package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.time.Instant;
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

/** Spec §2 rule 2: each Entry records its Account's entry_seq and balance_after. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class, Sessions.class})
class EntrySequenceTest {

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
  void each_entry_records_its_accounts_next_entry_seq_and_the_balance_after_it() {
    TransactionView first = transactionOf(makeTransfer(30_00));
    TransactionView second = transactionOf(makeTransfer(20_00));

    // A's first Entry is its funding, so its Payments' Entries are its second and third.
    assertThat(first.entries())
        .containsExactlyInAnyOrder(
            new EntryView(a.value(), "DEBIT", 30_00, 2, 70_00),
            new EntryView(b.value(), "CREDIT", 30_00, 1, 30_00));
    assertThat(second.entries())
        .containsExactlyInAnyOrder(
            new EntryView(a.value(), "DEBIT", 20_00, 3, 50_00),
            new EntryView(b.value(), "CREDIT", 20_00, 2, 50_00));
    assertThat(second.bookingTime()).isAfter(first.bookingTime());
  }

  private PaymentView makeTransfer(long amountPence) {
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
                    .formatted(a.value(), b.value(), amountPence))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(PaymentView.class)
            .returnResult()
            .getResponseBody();
    assertThat(payment).isNotNull();
    assertThat(payment.status()).isEqualTo("COMPLETED");
    return payment;
  }

  private TransactionView transactionOf(PaymentView payment) {
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

  record PaymentView(UUID id, String status, List<UUID> transactionIds) {}

  record TransactionView(UUID id, Instant bookingTime, List<EntryView> entries) {}

  record EntryView(
      UUID accountId, String side, long amountPence, long entrySeq, long balanceAfterPence) {}
}
