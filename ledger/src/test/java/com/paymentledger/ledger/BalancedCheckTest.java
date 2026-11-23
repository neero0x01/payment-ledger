package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The balanced check (spec §13): every interval it proves Σdebits = Σcredits, that the stored
 * Balance of each Account touched since the last run equals the Σ of its Entries, and that the Read
 * Model Balance of those Accounts equals the write-side Balance. Operators read the result in the
 * ledger_balanced and ledger_imbalance_pence gauges, with the time of the last run.
 *
 * <p>The check runs every 200 ms here instead of every 60 s. The gauges cover the whole ledger, so
 * the healthy ledger is checked first and each drift test asserts what its own drift adds.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ledger.balanced-check.interval=PT0.2S")
@AutoConfigureRestTestClient
@Import({
  TestcontainersConfiguration.class,
  ProjectorConfiguration.class,
  LedgerFixture.class,
  Sessions.class,
  TransactionPostedProducer.class
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BalancedCheckTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired TransactionPostedProducer events;

  @Test
  @Order(1)
  void a_balanced_ledger_reports_balanced_with_the_time_of_the_last_run() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient operator = sessions.operator();
    transfer(sessions.customerOwning(a), a, b, 10_00);

    BalancedCheckGauges.awaitRunStartedAfterNow(operator);

    assertThat(BalancedCheckGauges.balanced(operator)).isEqualTo(1.0);
    assertThat(BalancedCheckGauges.imbalancePence(operator)).isZero();
    assertThat(BalancedCheckGauges.lastRun(operator))
        .isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(5));
  }

  static void transfer(RestTestClient customer, AccountId from, AccountId to, long amountPence) {
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
        .isEqualTo(HttpStatus.CREATED);
  }
}
