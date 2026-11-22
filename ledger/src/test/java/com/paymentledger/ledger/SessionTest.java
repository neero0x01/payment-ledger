package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentledger.ledger.accounts.Logins;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.client.ExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Spec §8: Customers and Operators log in to a server-side session. The session cookie is
 * SameSite=Lax, and every state-changing request carries the CSRF token from its cookie in a
 * header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, LedgerFixture.class, Sessions.class})
class SessionTest {

  @Autowired RestTestClient http;
  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired Logins logins;

  AccountId a;
  AccountId b;

  @BeforeEach
  void givenAHas100PoundsAndBHasNothing() {
    a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    b = ledger.customerAccountWithBalance(Money.ofPence(0));
  }

  @Test
  void request_without_a_session_is_refused_as_unauthenticated() {
    http.get()
        .uri("/v1/accounts/{id}/balance", a.value())
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/unauthenticated");
  }

  @Test
  void logged_in_customer_sees_their_session_as_a_customer() {
    sessions
        .customerOwning(a)
        .get()
        .uri("/v1/session")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.role")
        .isEqualTo("CUSTOMER");
  }

  @Test
  void logged_in_operator_sees_their_session_as_an_operator() {
    sessions
        .operator()
        .get()
        .uri("/v1/session")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.role")
        .isEqualTo("OPERATOR");
  }

  @Test
  void session_cookie_is_http_only_and_same_site_lax() {
    String username = "customer-" + UUID.randomUUID();
    logins.addCustomerLogin(ledger.ownerOf(a), username, Sessions.PASSWORD);

    ExchangeResult login =
        sessions
            .postCredentials(username, Sessions.PASSWORD, sessions.csrfToken())
            .expectStatus()
            .isNoContent()
            .returnResult();

    ResponseCookie session = login.getResponseCookies().getFirst(Sessions.SESSION_COOKIE);
    assertThat(session).isNotNull();
    assertThat(session.isHttpOnly()).isTrue();
    assertThat(session.getSameSite()).isEqualToIgnoringCase("Lax");
  }

  @Test
  void login_with_a_wrong_password_is_refused_as_unauthenticated() {
    String username = "customer-" + UUID.randomUUID();
    logins.addCustomerLogin(ledger.ownerOf(a), username, Sessions.PASSWORD);

    sessions
        .postCredentials(username, "not the password", sessions.csrfToken())
        .expectStatus()
        .isUnauthorized()
        .expectCookie()
        .doesNotExist(Sessions.SESSION_COOKIE)
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/unauthenticated");
  }

  @Test
  void login_without_the_csrf_token_is_refused() {
    String username = "customer-" + UUID.randomUUID();
    logins.addCustomerLogin(ledger.ownerOf(a), username, Sessions.PASSWORD);

    http.post()
        .uri("/v1/sessions")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body("username=" + username + "&password=" + Sessions.PASSWORD.replace(' ', '+'))
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectCookie()
        .doesNotExist(Sessions.SESSION_COOKIE);
  }

  @Test
  void payment_without_the_csrf_header_is_refused_and_stores_nothing() {
    RestTestClient customer = sessions.customerOwning(a);
    RestTestClient withoutHeader =
        customer.mutate().defaultHeaders(headers -> headers.remove(Sessions.CSRF_HEADER)).build();

    withoutHeader
        .post()
        .uri("/v1/payments")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": 1000}
            """
                .formatted(a.value(), b.value()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.FORBIDDEN)
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("/problems/forbidden");

    customer
        .get()
        .uri("/v1/accounts/{id}/balance", a.value())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.balancePence")
        .isEqualTo(100_00);
  }
}
