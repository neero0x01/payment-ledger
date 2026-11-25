package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.TransactionPostedProducer.EventEntry;
import com.paymentledger.ledger.TransactionPostedProducer.TransactionPosted;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The balanced check (spec §13): every interval it proves Σdebits = Σcredits, that the stored
 * Balance of each Account touched since the last run equals the Σ of its Entries, and that the Read
 * Model Balance of those Accounts equals the write-side Balance. Operators read the result in the
 * ledger_balanced and ledger_imbalance_pence gauges, with the time of the last run.
 *
 * <p>The check runs every 200 ms here instead of every 60 s, and allows the Read Model a grace
 * period of 5 s instead of 60 s. The last test stops the projector. The gauges cover the whole
 * ledger, so the healthy ledger is checked first and each drift test asserts what its own drift
 * adds. For the same reason the context has its own Postgres and Kafka, closed after the class: on
 * the shared ones it would see other contexts' Accounts and Read Model events, and leave its drift
 * behind.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ledger.balanced-check.interval=PT0.2S",
      "ledger.balanced-check.read-model-grace=PT5S"
    })
@AutoConfigureRestTestClient
@Import({
  DedicatedContainersConfiguration.class,
  ProjectorConfiguration.class,
  LedgerFixture.class,
  Sessions.class,
  TransactionPostedProducer.class
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class BalancedCheckTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired TransactionPostedProducer events;
  @Autowired PostgreSQLContainer postgres;

  @Autowired
  @Qualifier(ProjectorConfiguration.PROJECTOR)
  ConfigurableApplicationContext projector;

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

  @Test
  @Order(2)
  void a_stored_balance_that_drifts_from_its_entries_is_detected() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient operator = sessions.operator();
    BalancedCheckGauges.awaitRunStartedAfterNow(operator);
    long before = BalancedCheckGauges.imbalancePence(operator);

    ledger.driftStoredBalance(a, Money.ofPence(7_77));
    transfer(sessions.customerOwning(a), a, b, 1_00);
    BalancedCheckGauges.awaitRunStartedAfterNow(operator);

    assertThat(BalancedCheckGauges.balanced(operator)).isZero();
    assertThat(BalancedCheckGauges.imbalancePence(operator)).isEqualTo(before + 7_77);
    // The drift stays reported on later runs, though nothing touches a again.
    BalancedCheckGauges.awaitRunStartedAfterNow(operator);
    assertThat(BalancedCheckGauges.imbalancePence(operator)).isEqualTo(before + 7_77);
  }

  @Test
  @Order(3)
  void a_read_model_balance_that_differs_from_the_write_side_is_detected() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient customer = sessions.customerOwning(a);
    RestTestClient operator = sessions.operator();
    awaitReadModelBalance(customer, a, 100_00);
    BalancedCheckGauges.awaitRunStartedAfterNow(operator);
    long before = BalancedCheckGauges.imbalancePence(operator);

    // A TransactionPosted the ledger never posted claims a's entry_seq 2 left it at £123.45.
    events.send(
        TransactionPosted.funding(
            Instant.now(),
            new EventEntry(new AccountId(UUID.randomUUID()), Side.DEBIT, 23_45, 1, 23_45),
            new EventEntry(a, Side.CREDIT, 23_45, 2, 123_45)));
    awaitReadModelBalance(customer, a, 123_45);
    // The ledger's own entry_seq 2 leaves a at £90.00.
    transfer(customer, a, b, 10_00);
    BalancedCheckGauges.awaitRunStartedAfterNow(operator);

    assertThat(BalancedCheckGauges.balanced(operator)).isZero();
    assertThat(BalancedCheckGauges.imbalancePence(operator)).isEqualTo(before + 33_45);
  }

  @Test
  @Order(4)
  void drift_on_an_account_touched_by_a_posting_that_commits_long_after_it_started_is_detected()
      throws Exception {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient customer = sessions.customerOwning(a);
    RestTestClient operator = sessions.operator();
    awaitReadModelBalance(customer, a, 100_00);
    BalancedCheckGauges.awaitRunStartedAfterNow(operator);
    long before = BalancedCheckGauges.imbalancePence(operator);

    try (Connection outboxHolder =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      outboxHolder.setAutoCommit(false);
      try (Statement statement = outboxHolder.createStatement()) {
        // Holds up the Payment after it has changed a's balance row, as it appends its events.
        statement.execute("lock table outbox in share mode");
      }
      CompletableFuture<Void> payment =
          CompletableFuture.runAsync(() -> transfer(customer, a, b, 10_00));
      Thread.sleep(7_000);
      // A TransactionPosted the ledger never posted claims a's entry_seq 2 left it at £123.45.
      events.send(
          TransactionPosted.funding(
              Instant.now(),
              new EventEntry(new AccountId(UUID.randomUUID()), Side.DEBIT, 23_45, 1, 23_45),
              new EventEntry(a, Side.CREDIT, 23_45, 2, 123_45)));
      awaitReadModelBalance(customer, a, 123_45);
      BalancedCheckGauges.awaitRunStartedAfterNow(operator);
      outboxHolder.commit();
      payment.get(30, TimeUnit.SECONDS);
    }
    // The Payment, committed only now, leaves a at £90.00 after entry_seq 2.
    BalancedCheckGauges.awaitRunStartedAfterNow(operator);

    assertThat(BalancedCheckGauges.balanced(operator)).isZero();
    assertThat(BalancedCheckGauges.imbalancePence(operator)).isEqualTo(before + 33_45);
  }

  @Test
  @Order(5)
  void an_account_the_read_model_trails_beyond_the_grace_period_is_drift() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient customer = sessions.customerOwning(a);
    RestTestClient operator = sessions.operator();
    awaitReadModelBalance(customer, a, 100_00);
    BalancedCheckGauges.awaitRunStartedAfterNow(operator);
    long before = BalancedCheckGauges.imbalancePence(operator);

    projector.close();
    transfer(customer, a, b, 10_00);

    // Within the grace period, a Read Model that trails is no drift.
    BalancedCheckGauges.awaitRunStartedAfterNow(operator);
    assertThat(BalancedCheckGauges.imbalancePence(operator)).isEqualTo(before);
    // Beyond it, a (written £90.00, projected £100.00) and b (written £10.00, never projected) are.
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> BalancedCheckGauges.imbalancePence(operator) == before + 20_00);
    assertThat(BalancedCheckGauges.balanced(operator)).isZero();
  }

  private static void awaitReadModelBalance(
      RestTestClient customer, AccountId account, long balancePence) {
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() ->
            ReadEndpoints.accounts(customer).get(0).get("balancePence").asLong() == balancePence);
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
