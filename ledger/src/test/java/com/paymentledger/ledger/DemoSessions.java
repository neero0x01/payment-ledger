package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentledger.ledger.DemoSessionTest.DemoSession;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Starts public demo sessions the way the landing page does: with the CSRF token from its cookie,
 * from a visitor's IP address, which the proxy in front of the ledger passes in X-Forwarded-For.
 */
final class DemoSessions {

  private DemoSessions() {}

  /** A demo session that started, with a client that sends its session cookie and CSRF token. */
  record Started(DemoSession body, RestTestClient client) {}

  /** A visitor IP address no other test uses. */
  static String newIp() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
  }

  /** Posts a demo session for the role from the IP address, without asserting on the answer. */
  static RestTestClient.ResponseSpec post(Sessions sessions, String role, String ip) {
    return post(sessions, role, ip, sessions.csrfToken());
  }

  private static RestTestClient.ResponseSpec post(
      Sessions sessions, String role, String ip, String csrf) {
    return sessions
        .anonymous()
        .post()
        .uri("/v1/demo-sessions")
        .header("X-Forwarded-For", ip)
        .cookie(Sessions.CSRF_COOKIE, csrf)
        .header(Sessions.CSRF_HEADER, csrf)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"role\": \"" + role + "\"}")
        .exchange();
  }

  /** Starts a demo session for the role from the IP address. */
  static Started start(Sessions sessions, String role, String ip) {
    String csrf = sessions.csrfToken();
    EntityExchangeResult<DemoSession> result =
        post(sessions, role, ip, csrf)
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(DemoSession.class)
            .returnResult();
    DemoSession body = result.getResponseBody();
    assertThat(body).isNotNull();
    String session = Sessions.cookie(result, Sessions.SESSION_COOKIE);
    return new Started(body, sessions.withSession(session, csrf));
  }
}
