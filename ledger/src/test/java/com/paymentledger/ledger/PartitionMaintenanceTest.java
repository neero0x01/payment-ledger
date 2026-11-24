package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The partition maintenance job (spec §10) creates Entry partitions three months ahead of the
 * current month, so the ledger never meets a month without one. Here it runs every second. The test
 * drops the next three months' partitions, so the context has a Postgres of its own.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ledger.partition-maintenance.cron=* * * * * *")
@AutoConfigureRestTestClient
@Import({
  DedicatedContainersConfiguration.class,
  LedgerFixture.class,
  Sessions.class,
  EntryPartitionDropper.class
})
@DirtiesContext
class PartitionMaintenanceTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired EntryPartitionDropper partitions;

  @Test
  void the_job_creates_partitions_three_months_ahead_of_the_current_month() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient operator = sessions.operator();
    Instant lastMomentThreeMonthsAhead =
        YearMonth.now(ZoneOffset.UTC)
            .plusMonths(4)
            .atDay(1)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)
            .minusNanos(1000);

    partitions.dropMonthsAhead(1);
    partitions.dropMonthsAhead(2);
    partitions.dropMonthsAhead(3);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() ->
            assertThatCode(() ->
                    ledger.fundingBookedAt(a, Money.ofPence(1_00), lastMomentThreeMonthsAhead))
                .doesNotThrowAnyException());
    assertThat(PartitionGauges.nextMonthMissing(operator)).isZero();
  }
}
