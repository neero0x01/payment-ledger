package com.paymentledger.fpssimulator;

import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plays the ledger's part: an HTTP server on a random port that answers every {@code POST
 * /v1/scheme/callbacks} with 200 and records the Scheme Outcome, its signature and when it came.
 */
final class LedgerStub {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A Scheme Outcome as the ledger received it. */
  record ReceivedOutcome(String body, @Nullable String signature, Instant receivedAt) {

    String endToEndId() {
      return json().path("endToEndId").asString();
    }

    String status() {
      return json().path("status").asString();
    }

    @Nullable String reasonCode() {
      JsonNode code = json().get("reasonCode");
      return code == null || code.isNull() ? null : code.asString();
    }

    private JsonNode json() {
      return JSON.readTree(body);
    }
  }

  private final HttpServer server;
  private final List<ReceivedOutcome> received = new CopyOnWriteArrayList<>();

  LedgerStub() {
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext(
        "/v1/scheme/callbacks",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          received.add(
              new ReceivedOutcome(
                  body, exchange.getRequestHeaders().getFirst("Scheme-Signature"), Instant.now()));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
  }

  String baseUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  /** Every Scheme Outcome received so far for the End-to-End Id. */
  List<ReceivedOutcome> outcomesFor(String endToEndId) {
    return received.stream().filter(c -> c.endToEndId().equals(endToEndId)).toList();
  }

  /** Waits up to 10 s until the ledger has received this many Scheme Outcomes for the id. */
  List<ReceivedOutcome> awaitOutcomesFor(String endToEndId, int count) {
    return await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> outcomesFor(endToEndId), list -> list.size() >= count);
  }

  ReceivedOutcome awaitOutcomeFor(String endToEndId) {
    return awaitOutcomesFor(endToEndId, 1).getFirst();
  }
}
