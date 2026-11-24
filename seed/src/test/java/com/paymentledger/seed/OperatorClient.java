package com.paymentledger.seed;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Talks to a running ledger over HTTP as an Operator, the way the web page does: it picks up the
 * CSRF token from its cookie, posts the credentials to {@code /v1/sessions}, and then sends the
 * session cookie on every request.
 */
final class OperatorClient {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final URI base;
  private final CookieManager cookies = new CookieManager();
  private final HttpClient http;

  private OperatorClient(URI base) {
    this.base = base;
    this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
  }

  /** A client logged in to the ledger at the base URI with the Operator's credentials. */
  static OperatorClient logIn(URI base, String username, String password) {
    OperatorClient client = new OperatorClient(base);
    client.send(HttpRequest.newBuilder(base.resolve("/v1/session")).GET().build());
    String csrf = csrfToken(client.cookies, base);
    String form = "username=" + encode(username) + "&password=" + encode(password);
    HttpResponse<String> login =
        client.send(
            HttpRequest.newBuilder(base.resolve("/v1/sessions"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build());
    if (login.statusCode() != 204) {
      throw new AssertionError("Login answered " + login.statusCode() + ": " + login.body());
    }
    return client;
  }

  /** GETs the path and returns its JSON body, refusing any status but 200. */
  JsonNode get(String path) {
    HttpResponse<String> response = send(HttpRequest.newBuilder(base.resolve(path)).GET().build());
    if (response.statusCode() != 200) {
      throw new AssertionError(
          "GET " + path + " answered " + response.statusCode() + ": " + response.body());
    }
    return JSON.readTree(response.body());
  }

  /** A response's status and JSON body. */
  record Response(int status, JsonNode body) {}

  /**
   * POSTs the JSON body to the path with a new Idempotency-Key and the CSRF token, and returns the
   * status and JSON body, whatever the status.
   */
  Response post(String path, String json) {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(base.resolve(path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-XSRF-TOKEN", csrfToken(cookies, base))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    return new Response(response.statusCode(), JSON.readTree(response.body()));
  }

  private static String csrfToken(CookieManager cookies, URI base) {
    return cookies.getCookieStore().get(base).stream()
        .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElseThrow(() -> new AssertionError("No XSRF-TOKEN cookie"));
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
