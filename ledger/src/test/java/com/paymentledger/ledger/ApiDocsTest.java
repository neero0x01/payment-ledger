package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.ExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Spec §7: the OpenAPI document and Swagger UI are public, with Swagger UI at /docs. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class ApiDocsTest {

  @Autowired RestTestClient http;

  @Test
  void swagger_ui_is_public_at_docs() {
    ExchangeResult docs = http.get().uri("/docs").exchange().returnResult();
    URI page = docs.getResponseHeaders().getLocation();
    assertThat(docs.getStatus().is3xxRedirection()).as("/docs redirects to Swagger UI").isTrue();
    assertThat(page).isNotNull();

    http.get()
        .uri(page.getPath())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(html -> assertThat(html).containsIgnoringCase("swagger-ui"));
  }

  @Test
  void openapi_document_is_public_and_describes_the_ledger_api() {
    http.get()
        .uri("/v3/api-docs")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.openapi")
        .value(version -> assertThat(version.toString()).startsWith("3.1"))
        .jsonPath("$.paths['/v1/payments']")
        .exists()
        .jsonPath("$.paths['/v1/demo-sessions']")
        .exists();
  }
}
