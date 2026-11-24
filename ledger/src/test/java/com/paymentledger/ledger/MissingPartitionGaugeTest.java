package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The missing next-month partition gauge (spec §11) reads 1 when next month has no Entry partition,
 * so an alert fires well before Entries booked then would be refused. Here the partition
 * maintenance job's schedule is switched off, and the test drops next month's partition, so the
 * context has a Postgres of its own.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ledger.partition-maintenance.cron=-")
@AutoConfigureRestTestClient
@Import({
  DedicatedContainersConfiguration.class,
  LedgerFixture.class,
  Sessions.class,
  EntryPartitionDropper.class
})
@DirtiesContext
class MissingPartitionGaugeTest {

  @Autowired Sessions sessions;
  @Autowired EntryPartitionDropper partitions;

  @Test
  void the_gauge_reads_1_only_once_next_month_has_no_partition() {
    RestTestClient operator = sessions.operator();
    assertThat(PartitionGauges.nextMonthMissing(operator)).isZero();

    partitions.dropMonthsAhead(1);

    assertThat(PartitionGauges.nextMonthMissing(operator)).isEqualTo(1.0);
  }
}
