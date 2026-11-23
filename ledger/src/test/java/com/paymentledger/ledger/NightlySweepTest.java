package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The balanced check's nightly full sweep (spec §13) checks every Account's stored Balance against
 * its Entries, so drift on an Account nothing touches is still found. Here the sweep runs every
 * second and the touched-Accounts run only once, at startup. The sweep covers every Account in the
 * ledger, so the context has its own Postgres and Kafka, closed after the class: on the shared ones
 * it would also find other tests' drift, and leave its own behind.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ledger.balanced-check.interval=PT1H",
      "ledger.balanced-check.full-sweep-cron=* * * * * *"
    })
@AutoConfigureRestTestClient
@Import({
  DedicatedContainersConfiguration.class,
  ProjectorConfiguration.class,
  LedgerFixture.class,
  Sessions.class
})
@DirtiesContext
class NightlySweepTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;

  @Test
  void full_sweep_finds_drift_on_an_account_nothing_touched() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(50_00));
    RestTestClient operator = sessions.operator();
    BalancedCheckGauges.awaitRunStartedAfterNow(operator);
    assertThat(BalancedCheckGauges.balanced(operator)).isEqualTo(1.0);

    ledger.driftStoredBalance(a, Money.ofPence(-3_21));
    BalancedCheckGauges.awaitRunStartedAfterNow(operator);

    assertThat(BalancedCheckGauges.balanced(operator)).isZero();
    assertThat(BalancedCheckGauges.imbalancePence(operator)).isEqualTo(3_21);
  }
}
