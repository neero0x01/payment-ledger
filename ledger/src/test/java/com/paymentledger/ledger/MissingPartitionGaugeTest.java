package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The missing next-month partition gauge (spec §11) reads 1 when next month has no Entry partition,
 * so an alert fires well before Entries booked then would be refused. Here the partition
 * maintenance job's schedule is switched off, and the test moves its clock to a month no test ever
 * creates partitions for.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ledger.partition-maintenance.cron=-")
@AutoConfigureRestTestClient
@Import({
  TestcontainersConfiguration.class,
  MovableClock.Configuration.class,
  LedgerFixture.class,
  Sessions.class
})
class MissingPartitionGaugeTest {

  static final Instant JUNE_2100 = Instant.parse("2100-06-14T12:00:00Z");

  @Autowired MovableClock clock;
  @Autowired Sessions sessions;

  @Test
  void the_gauge_reads_0_while_next_month_has_a_partition() {
    RestTestClient operator = sessions.operator();

    assertThat(PartitionGauges.nextMonthMissing(operator)).isZero();
  }

  @Test
  void the_gauge_reads_1_when_next_month_has_no_partition() {
    RestTestClient operator = sessions.operator();

    clock.moveTo(JUNE_2100);
    try {
      assertThat(PartitionGauges.nextMonthMissing(operator)).isEqualTo(1.0);
    } finally {
      clock.moveTo(Instant.now());
    }
  }
}
