package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Entries are range-partitioned by month on Booking Time (ADR 0010). Flyway creates a partition for
 * every month from the seed's first, November 2025, through three months ahead. There is no DEFAULT
 * partition, so an Entry booked in a month without one is refused rather than parked somewhere that
 * would block creating its real partition later.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, LedgerFixture.class})
class EntryPartitionsTest {

  static final Instant SEED_START = Instant.parse("2025-11-01T00:00:00Z");

  @Autowired LedgerFixture ledger;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate transaction;

  AccountId a;

  @BeforeEach
  void givenACustomerAccount() {
    a = ledger.customerAccountWithBalance(Money.ofPence(0));
  }

  @Test
  void entries_booked_in_any_month_from_the_seed_start_through_three_months_ahead_are_accepted() {
    YearMonth threeMonthsAhead = YearMonth.now(ZoneOffset.UTC).plusMonths(3);
    Instant lastMomentCovered =
        threeMonthsAhead
            .plusMonths(1)
            .atDay(1)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)
            .minusNanos(1000);

    assertThatCode(
            () -> {
              ledger.fundingBookedAt(a, Money.ofPence(1_00), SEED_START);
              ledger.fundingBookedAt(a, Money.ofPence(1_00), lastMomentCovered);
            })
        .doesNotThrowAnyException();
  }

  @Test
  void an_entry_booked_in_a_month_without_a_partition_is_refused() {
    Instant beforeTheSeed = SEED_START.minusNanos(1000);

    assertThatThrownBy(() -> ledger.fundingBookedAt(a, Money.ofPence(1_00), beforeTheSeed))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("no partition of relation \"entry\" found");
  }

  /**
   * The As-At Balance query, as JdbcPosting sends it, reads the newest Entry at or before the
   * moment straight from the index, newest partition first, without a sort and without visiting the
   * table. Sequential and bitmap scans are switched off because the test tables are too small for
   * the planner to prefer an index otherwise.
   */
  @Test
  void the_as_at_balance_query_is_an_ordered_index_only_scan() {
    ledger.fundingBookedAt(a, Money.ofPence(1_00), SEED_START);

    List<String> plan =
        transaction.execute(status -> {
          jdbc.sql("set local enable_seqscan = off").update();
          jdbc.sql("set local enable_bitmapscan = off").update();
          return jdbc.sql(
                  """
                  explain
                  select balance_after_pence
                  from entry
                  where account_id = :accountId and booked_at <= :asAt
                  order by booked_at desc, entry_seq desc
                  limit 1
                  """)
              .param("accountId", a.value())
              .param("asAt", Instant.now().atOffset(ZoneOffset.UTC))
              .query(String.class)
              .list();
        });

    assertThat(String.join("\n", plan))
        .contains("Index Only Scan using entry_")
        .doesNotContain("Sort")
        .doesNotContain("Index Scan")
        .doesNotContain("Seq Scan");
  }

  /**
   * Entries are insert-only, so only insert-driven autovacuum ever sets their pages all-visible,
   * which index-only scans need. Every partition is vacuumed after a fixed number of inserts rather
   * than a fraction of its size, so a growing month's visibility map stays current.
   */
  @Test
  void every_entry_partition_is_vacuumed_after_a_fixed_number_of_inserts() {
    List<String> untuned =
        jdbc.sql(
                """
                select c.relname
                from pg_inherits i
                join pg_class c on c.oid = i.inhrelid
                where i.inhparent = 'entry'::regclass
                  and not coalesce(c.reloptions, '{}') @> array[
                      'autovacuum_vacuum_insert_scale_factor=0',
                      'autovacuum_vacuum_insert_threshold=10000']
                """)
            .query(String.class)
            .list();
    long partitions =
        jdbc.sql("select count(*) from pg_inherits where inhparent = 'entry'::regclass")
            .query(Long.class)
            .single();

    assertThat(partitions).isPositive();
    assertThat(untuned).isEmpty();
  }
}
