package com.paymentledger.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

/**
 * Logs in the 200 load-test Customers the seed made ({@code load-test-001} to {@code
 * load-test-200}) before a run, and finds the load-test Account each one owns. Logging in hashes a
 * password, so it happens once per Customer here, outside the measured traffic; every virtual user
 * then reuses its Customer's session and CSRF token (spec §8).
 *
 * <p>The last ten load-test Accounts are kept below the most one Payment may be, £1,000,000.00, so
 * a Payment of that much from one of them is always Rejected for Insufficient Funds. Every
 * load-test Account starts with exactly that much and the Internal Transfers move money between
 * them at random, so a Payment of the maximum from any other Account could go through and empty it.
 */
final class LoadTestCustomers {

  /** How many load-test Customers the seed makes, each with one load-test Account. */
  static final int COUNT = 200;

  private static final int BELOW_MAXIMUM_COUNT = 10;

  /** The most one Payment may be (the ledger's PaymentService.MAXIMUM_AMOUNT). */
  static final long MAXIMUM_PAYMENT_PENCE = 1_000_000_00L;

  private static final ObjectMapper JSON = new ObjectMapper();

  private LoadTestCustomers() {}

  /** A load-test Customer's session, ready to make Payments from their load-test Account. */
  record LoggedIn(String accountId, String sessionCookie, String xsrfToken) {}

  /**
   * @param paying the Customers whose Accounts pay one another and external Payees
   * @param belowMaximum the Customers whose Accounts hold less than the most one Payment may be,
   *     and are never paid into during a run
   */
  record LoggedInCustomers(List<LoggedIn> paying, List<LoggedIn> belowMaximum) {}

  /**
   * Logs every load-test Customer in, and brings each Account kept for Insufficient Funds below the
   * maximum Payment with an Internal Transfer to a paying Account, if it is not already.
   */
  static LoggedInCustomers logIn(LoadTestSettings settings) {
    HttpClient http = HttpClient.newHttpClient();
    List<LoggedIn> customers = logInAll(http, settings);
    LoggedInCustomers split =
        new LoggedInCustomers(
            customers.subList(0, COUNT - BELOW_MAXIMUM_COUNT),
            customers.subList(COUNT - BELOW_MAXIMUM_COUNT, COUNT));
    try {
      for (LoggedIn customer : split.belowMaximum()) {
        drawDown(http, settings, customer, split.paying().getFirst());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    return split;
  }

  private static void drawDown(
      HttpClient http, LoadTestSettings settings, LoggedIn customer, LoggedIn payee)
      throws IOException, InterruptedException {
    HttpResponse<String> balance =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        settings.baseUrl() + "/v1/accounts/" + customer.accountId() + "/balance"))
                .header("Cookie", "JSESSIONID=" + customer.sessionCookie())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    long balancePence = JSON.readTree(balance.body()).get("balancePence").asLong();
    long excessPence = balancePence - (MAXIMUM_PAYMENT_PENCE - 1);
    if (excessPence <= 0) {
      return;
    }
    String body =
        """
        {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": %d}\
        """
            .formatted(customer.accountId(), payee.accountId(), excessPence);
    HttpResponse<String> transfer =
        http.send(
            HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/v1/payments"))
                .header("Content-Type", "application/json")
                .header(
                    "Cookie",
                    "JSESSIONID="
                        + customer.sessionCookie()
                        + "; XSRF-TOKEN="
                        + customer.xsrfToken())
                .header("X-XSRF-TOKEN", customer.xsrfToken())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (transfer.statusCode() != 201
        || !"COMPLETED".equals(JSON.readTree(transfer.body()).path("status").asText())) {
      throw new IllegalStateException(
          "Could not bring Account "
              + customer.accountId()
              + " below the maximum Payment: "
              + transfer.statusCode()
              + " "
              + transfer.body());
    }
  }

  private static List<LoggedIn> logInAll(HttpClient http, LoadTestSettings settings) {
    try (var executor = Executors.newFixedThreadPool(16)) {
      List<Future<LoggedIn>> logins =
          IntStream.rangeClosed(1, COUNT)
              .mapToObj(n -> "load-test-%03d".formatted(n))
              .map(username -> executor.submit(() -> logIn(http, settings, username)))
              .toList();
      List<LoggedIn> customers = new ArrayList<>();
      for (Future<LoggedIn> login : logins) {
        customers.add(login.get());
      }
      return customers;
    } catch (ExecutionException e) {
      throw new IllegalStateException("A load-test Customer could not log in", e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static LoggedIn logIn(HttpClient http, LoadTestSettings settings, String username)
      throws IOException, InterruptedException {
    // Any first request gets the CSRF cookie, which the login form must echo in the header.
    HttpResponse<Void> anonymous =
        http.send(
            HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/v1/session")).build(),
            HttpResponse.BodyHandlers.discarding());
    String anonymousXsrf = cookies(anonymous).get("XSRF-TOKEN");
    if (anonymousXsrf == null) {
      throw new IllegalStateException(
          "The ledger at " + settings.baseUrl() + " sent no XSRF-TOKEN");
    }

    String form =
        "username="
            + URLEncoder.encode(username, StandardCharsets.UTF_8)
            + "&password="
            + URLEncoder.encode(settings.password(), StandardCharsets.UTF_8);
    HttpResponse<String> login =
        http.send(
            HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/v1/sessions"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", "XSRF-TOKEN=" + anonymousXsrf)
                .header("X-XSRF-TOKEN", anonymousXsrf)
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (login.statusCode() != 204) {
      throw new IllegalStateException(
          username + " could not log in: " + login.statusCode() + " " + login.body());
    }
    Map<String, String> cookies = cookies(login);
    String session = cookies.get("JSESSIONID");
    // Logging in replaces the CSRF token (WebSecurity).
    String xsrf = cookies.getOrDefault("XSRF-TOKEN", anonymousXsrf);
    if (session == null) {
      throw new IllegalStateException(username + " logged in but got no session cookie");
    }

    HttpResponse<String> accounts =
        http.send(
            HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/v1/accounts"))
                .header("Cookie", "JSESSIONID=" + session)
                .build(),
            HttpResponse.BodyHandlers.ofString());
    JsonNode account =
        findCustomerAccount(JSON.readTree(accounts.body()))
            .orElseThrow(() -> new IllegalStateException(username + " owns no Customer Account"));
    return new LoggedIn(account.get("id").asText(), session, xsrf);
  }

  private static Optional<JsonNode> findCustomerAccount(JsonNode accounts) {
    for (JsonNode account : accounts) {
      if ("CUSTOMER".equals(account.path("kind").asText())) {
        return Optional.of(account);
      }
    }
    return Optional.empty();
  }

  private static Map<String, String> cookies(HttpResponse<?> response) {
    Map<String, String> cookies = new HashMap<>();
    for (String header : response.headers().allValues("Set-Cookie")) {
      String pair = header.split(";", 2)[0];
      int equals = pair.indexOf('=');
      if (equals > 0) {
        cookies.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
      }
    }
    return cookies;
  }
}
