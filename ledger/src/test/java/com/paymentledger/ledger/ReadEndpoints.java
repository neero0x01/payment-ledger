package com.paymentledger.ledger;

import com.paymentledger.ledger.shared.AccountId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the Read Model through the ledger HTTP API: the Account list and Statements. */
final class ReadEndpoints {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ReadEndpoints() {}

  /** GET /v1/accounts: the Accounts the client may see. */
  static JsonNode accounts(RestTestClient client) {
    return get(client, uri -> uri.path("/v1/accounts").build());
  }

  /** One page of GET /v1/accounts/{id}/statement with the query parameters given. */
  static JsonNode statementPage(RestTestClient client, AccountId account, String... query) {
    return get(
        client,
        uri -> {
          uri.path("/v1/accounts/{id}/statement");
          for (int i = 0; i < query.length; i += 2) {
            uri.queryParam(query[i], query[i + 1]);
          }
          return uri.build(account.value());
        });
  }

  /** Every Statement Line of the Account, following nextCursor from page to page. */
  static List<JsonNode> statementLines(RestTestClient client, AccountId account) {
    List<JsonNode> lines = new ArrayList<>();
    JsonNode page = statementPage(client, account);
    page.get("lines").forEach(lines::add);
    while (!page.get("nextCursor").isNull()) {
      page = statementPage(client, account, "cursor", page.get("nextCursor").asString());
      page.get("lines").forEach(lines::add);
    }
    return lines;
  }

  private static JsonNode get(
      RestTestClient client, java.util.function.Function<UriBuilder, java.net.URI> uri) {
    String body =
        client
            .get()
            .uri(uri)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    return JSON.readTree(body);
  }
}
