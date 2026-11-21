package com.paymentledger.ledger;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Scenario 1: Internal Transfer [M1]. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class})
class InternalTransferTest {

  @Autowired RestTestClient http;
  @Autowired LedgerFixture ledger;

  AccountId a;
  AccountId b;

  @BeforeEach
  void givenAHas100PoundsAndBHasNothing() {
    a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    b = ledger.customerAccountWithBalance(Money.ofPence(0));
  }

  @Test
  void internal_transfer_returns_201_with_the_payment_completed() {
    http.post()
        .uri("/v1/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .body(transfer(a, b, 30_00))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectHeader()
        .exists("Location")
        .expectBody()
        .jsonPath("$.id").isNotEmpty()
        .jsonPath("$.type").isEqualTo("INTERNAL_TRANSFER")
        .jsonPath("$.status").isEqualTo("COMPLETED")
        .jsonPath("$.amountPence").isEqualTo(30_00);
  }

  private static String transfer(AccountId from, AccountId to, long amountPence) {
    return """
        {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": %d}
        """
        .formatted(from.value(), to.value(), amountPence);
  }
}
