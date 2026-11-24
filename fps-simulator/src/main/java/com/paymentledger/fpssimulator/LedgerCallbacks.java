package com.paymentledger.fpssimulator;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends Scheme Outcomes to the ledger's {@code POST /v1/scheme/callbacks} as a slimmed-down ISO
 * 20022 pacs.002, signed with the HMAC-SHA256 of the raw body under the shared key, as lowercase
 * hex, in the {@code Scheme-Signature} header (ADR 0008).
 */
@Component
class LedgerCallbacks {

  static final String SIGNATURE_HEADER = "Scheme-Signature";
  private static final String HMAC = "HmacSHA256";

  private final RestClient ledger;
  private final JsonMapper json;
  private final SecretKeySpec key;

  LedgerCallbacks(
      @Value("${fpssim.ledger-base-url}") String ledgerBaseUrl,
      @Value("${fpssim.callback-hmac-key}") String key,
      JsonMapper json) {
    this.ledger = RestClient.create(ledgerBaseUrl);
    this.json = json;
    this.key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC);
  }

  /** Sends the Scheme Outcome; throws if the ledger can't be reached or refuses it. */
  void send(String endToEndId, SchemeOutcome outcome) {
    byte[] body = json.writeValueAsBytes(Pacs002Lite.of(endToEndId, outcome));
    ledger
        .post()
        .uri("/v1/scheme/callbacks")
        .contentType(MediaType.APPLICATION_JSON)
        .header(SIGNATURE_HEADER, HexFormat.of().formatHex(hmac(body)))
        .body(body)
        .retrieve()
        .toBodilessEntity();
  }

  private byte[] hmac(byte[] body) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(key);
      return mac.doFinal(body);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
    }
  }

  /** The callback's JSON body: the End-to-End Id, ACSC or RJCT, and an RJCT's reason code. */
  record Pacs002Lite(String endToEndId, String status, @Nullable String reasonCode) {

    static Pacs002Lite of(String endToEndId, SchemeOutcome outcome) {
      SchemeOutcome.ReasonCode code = outcome.reasonCode();
      return new Pacs002Lite(
          endToEndId, outcome.status().name(), code == null ? null : code.name());
    }
  }
}
