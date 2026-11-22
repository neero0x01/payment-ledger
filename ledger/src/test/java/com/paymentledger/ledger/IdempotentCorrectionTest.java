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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Scenario 7 [M3]: Correction requests are safe to retry, as Payments are in Scenario 2. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class, Sessions.class})
class IdempotentCorrectionTest {

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;

  RestTestClient operator;

  AccountId a;
  AccountId b;

  @BeforeEach
  void givenAHas100PoundsAndBHasNothing() {
    a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    b = ledger.customerAccountWithBalance(Money.ofPence(0));
    operator = sessions.operator();
  }

  @Test
  void a_correction_without_an_idempotency_key_is_refused_with_400_and_posts_nothing() {
    operator
        .post()
        .uri("/v1/corrections")
        .contentType(MediaType.APPLICATION_JSON)
        .body(goodwill(a, 5_00))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectHeader()
        .doesNotExist("Location")
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/idempotency-key-missing");

    assertThat(balancePenceOf(a)).isEqualTo(100_00);
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

  private static String goodwill(AccountId account, long amountPence) {
    return """
    {"kind": "ADJUSTMENT", "reason": "GOODWILL", "note": "Sorry for the delay",
     "accountId": "%s", "amountPence": %d, "direction": "CREDIT"}
    """
        .formatted(account.value(), amountPence);
  }
}
