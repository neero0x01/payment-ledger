package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

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
}
