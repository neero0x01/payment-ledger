package com.paymentledger.ledger.shared.internal;

import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI 3.1 document springdoc generates, public with Swagger UI at {@code /docs} (spec §7).
 */
@Configuration(proxyBeanMethods = false)
class ApiDocs {

  /**
   * Names the API, and writes each path the way clients call it: springdoc fills the {@code
   * {version}} segment with the bare version, {@code /1/payments}, where the path is {@code
   * /v1/payments}.
   */
  @Bean
  OpenApiCustomizer ledgerApiDocs() {
    return openApi -> {
      openApi.setInfo(
          new Info()
              .title("Payment Ledger API")
              .version("v1")
              .description(
                  "A double-entry payment ledger. Errors are RFC 9457 problem details with a"
                      + " stable type URI under /problems/."));
      Paths versioned = new Paths();
      if (openApi.getPaths() != null) {
        openApi
            .getPaths()
            .forEach(
                (path, item) -> versioned.addPathItem(path.replaceFirst("^/1/", "/v1/"), item));
      }
      openApi.setPaths(versioned);
    };
  }
}
