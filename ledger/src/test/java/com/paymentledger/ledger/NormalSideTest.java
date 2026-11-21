package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Every Account's Balance is reported on its Normal Side (ADR 0003). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class, Sessions.class})
class NormalSideTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;

  RestTestClient operator;

  @BeforeEach
  void givenAnOperator() {
    operator = sessions.operator();
  }

  @Test
  void credit_normal_customer_account_reports_its_credits_as_a_positive_balance() {
    AccountId customer = ledger.customerAccountWithBalance(Money.ofPence(100_00));

    assertThat(balancePenceOf(customer)).isEqualTo(100_00);
  }

  @Test
  void debit_normal_funding_account_reports_its_debits_as_a_positive_balance() {
    long before = balancePenceOf(ledger.funding());

    ledger.customerAccountWithBalance(Money.ofPence(100_00));

    assertThat(balancePenceOf(ledger.funding())).isEqualTo(before + 100_00);
  }

  private long balancePenceOf(AccountId account) {
    BalanceView balance =
        operator
            .get()
            .uri("/v1/accounts/{id}/balance", account.value())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(BalanceView.class)
            .returnResult()
            .getResponseBody();
    assertThat(balance).isNotNull();
    return balance.balancePence();
  }

  record BalanceView(UUID accountId, long balancePence) {}
}
