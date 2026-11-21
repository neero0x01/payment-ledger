package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentledger.ledger.accounts.Logins;
import com.paymentledger.ledger.shared.AccountId;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.client.ExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Logs in through the ledger HTTP API the way the web page does: it picks up the CSRF token from
 * its cookie, posts the credentials to {@code /v1/sessions}, and then sends the session cookie and
 * the token, cookie to header, on every request.
 */
@Component
public class Sessions {

  static final String CSRF_COOKIE = "XSRF-TOKEN";
  static final String CSRF_HEADER = "X-XSRF-TOKEN";
  static final String SESSION_COOKIE = "JSESSIONID";
  static final String PASSWORD = "correct horse battery staple";

  private final ObjectProvider<RestTestClient> http;
  private final LedgerFixture ledger;
  private final Logins logins;

  Sessions(ObjectProvider<RestTestClient> http, LedgerFixture ledger, Logins logins) {
    this.http = http;
    this.ledger = ledger;
    this.logins = logins;
  }

  /** A client logged in as the Customer who owns the Customer Account. */
  public RestTestClient customerOwning(AccountId account) {
    String username = "customer-" + UUID.randomUUID();
    logins.addCustomerLogin(ledger.ownerOf(account), username, PASSWORD);
    return logIn(username, PASSWORD);
  }

  /** A client logged in as an Operator. */
  public RestTestClient operator() {
    String username = "operator-" + UUID.randomUUID();
    logins.addOperatorLogin(username, PASSWORD);
    return logIn(username, PASSWORD);
  }

  /** A client logged in with the username and password. */
  public RestTestClient logIn(String username, String password) {
    ExchangeResult login =
        postCredentials(username, password, csrfToken())
            .expectStatus()
            .isNoContent()
            .returnResult();
    String session = cookie(login, SESSION_COOKIE);
    String token = cookie(login, CSRF_COOKIE);
    return http.getObject()
        .mutate()
        .defaultCookie(SESSION_COOKIE, session)
        .defaultCookie(CSRF_COOKIE, token)
        .defaultHeader(CSRF_HEADER, token)
        .build();
  }

  /** A CSRF token, which any response to a client without one sets as a cookie. */
  public String csrfToken() {
    return cookie(http.getObject().get().uri("/v1/session").exchange().returnResult(), CSRF_COOKIE);
  }

  /** Posts a login form with the CSRF token, as the page does. */
  public RestTestClient.ResponseSpec postCredentials(
      String username, String password, String csrf) {
    return http.getObject()
        .post()
        .uri("/v1/sessions")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .cookie(CSRF_COOKIE, csrf)
        .header(CSRF_HEADER, csrf)
        .body("username=" + encode(username) + "&password=" + encode(password))
        .exchange();
  }

  /** The cookie's value as a browser keeps it: the last Set-Cookie of that name wins. */
  static String cookie(ExchangeResult result, String name) {
    List<ResponseCookie> cookies = result.getResponseCookies().getOrDefault(name, List.of());
    assertThat(cookies).as("Set-Cookie %s", name).isNotEmpty();
    return cookies.getLast().getValue();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
