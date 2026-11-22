package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Scenario 9: the As-At Balance of an Account at a past moment, on the Booking Time timeline. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class, Sessions.class})
class AsAtBalanceTest {

  static final Instant OCTOBER_1ST = Instant.parse("2026-10-01T09:00:00Z");
  static final Instant OCTOBER_5TH = Instant.parse("2026-10-05T09:00:00Z");
  static final Instant OCTOBER_10TH = Instant.parse("2026-10-10T09:00:00Z");
  static final Instant OCTOBER_20TH = Instant.parse("2026-10-20T09:00:00Z");

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;

  AccountId a;
  RestTestClient operator;

  @BeforeEach
  void givenAHasNoEntriesYet() {
    a = ledger.customerAccountWithBalance(Money.ofPence(0));
    operator = sessions.operator();
  }

  @Test
  void as_at_balance_is_the_balance_after_the_latest_entry_booked_at_or_before_the_moment() {
    ledger.fundingBookedAt(a, Money.ofPence(50_00), OCTOBER_1ST);
    ledger.fundingBookedAt(a, Money.ofPence(20_00), OCTOBER_10TH);
    ledger.fundingBookedAt(a, Money.ofPence(5_00), OCTOBER_10TH);

    assertThat(balanceAsAt(operator, a, OCTOBER_1ST)).isEqualTo(50_00);
    assertThat(balanceAsAt(operator, a, OCTOBER_5TH)).isEqualTo(50_00);
    // Two Entries booked at the same moment: the one with the higher entry_seq wins.
    assertThat(balanceAsAt(operator, a, OCTOBER_10TH)).isEqualTo(75_00);
    assertThat(balanceAsAt(operator, a, OCTOBER_20TH)).isEqualTo(75_00);
  }

  private static long balanceAsAt(RestTestClient client, AccountId account, Instant asAt) {
    BalanceView balance =
        client
            .get()
            .uri("/v1/accounts/{id}/balance?asAt={asAt}", account.value(), asAt.toString())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(BalanceView.class)
            .returnResult()
            .getResponseBody();
    assertThat(balance).isNotNull();
    assertThat(balance.accountId()).isEqualTo(account.value());
    return balance.balancePence();
  }

  record BalanceView(UUID accountId, long balancePence) {}
}
