package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The partition maintenance job (spec §10) creates Entry partitions three months ahead of the
 * current month, so the ledger never meets a month without one. Here it runs every second, and the
 * test moves the job's clock far into the future, to months no other test touches.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ledger.partition-maintenance.cron=* * * * * *")
@AutoConfigureRestTestClient
@Import({
  TestcontainersConfiguration.class,
  MovableClock.Configuration.class,
  LedgerFixture.class,
  Sessions.class
})
class PartitionMaintenanceTest {

  static final Instant MAY_2090 = Instant.parse("2090-05-14T12:00:00Z");
  static final Instant LAST_MOMENT_OF_AUGUST_2090 = Instant.parse("2090-08-31T23:59:59.999999Z");

  @Autowired MovableClock clock;
  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;

  @Test
  void the_job_creates_partitions_three_months_ahead_of_the_current_month() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient operator = sessions.operator();

    clock.moveTo(MAY_2090);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() ->
            assertThatCode(() ->
                    ledger.fundingBookedAt(a, Money.ofPence(1_00), LAST_MOMENT_OF_AUGUST_2090))
                .doesNotThrowAnyException());
    assertThat(PartitionGauges.nextMonthMissing(operator)).isZero();
  }
}
