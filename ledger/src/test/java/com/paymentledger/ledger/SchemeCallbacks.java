package com.paymentledger.ledger;

import com.paymentledger.ledger.payments.EndToEndId;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Plays the Faster Payments Scheme's part in tests: sends Scheme Outcomes (pacs.002-lite) to the
 * ledger's {@code POST /v1/scheme/callbacks}, signed with HMAC-SHA256 of the raw body in the {@code
 * Scheme-Signature} header as lowercase hex, the way the simulator does.
 */
@Component
public class SchemeCallbacks {

  public static final String SIGNATURE_HEADER = "Scheme-Signature";

  private final ObjectProvider<RestTestClient> http;
  private final String key;

  SchemeCallbacks(
      ObjectProvider<RestTestClient> http,
      @Value("${ledger.scheme.callback-hmac-key}") String key) {
    this.http = http;
    this.key = key;
  }

  /** An accepted (ACSC) Scheme Outcome for the End-to-End Id, correctly signed. */
  public RestTestClient.ResponseSpec accept(EndToEndId endToEndId) {
    return send(
        """
        {"endToEndId": "%s", "status": "ACSC"}
        """
            .formatted(endToEndId.value()));
  }

  /** A rejected (RJCT) Scheme Outcome with the reason code, correctly signed. */
  public RestTestClient.ResponseSpec reject(EndToEndId endToEndId, String reasonCode) {
    return send(
        """
        {"endToEndId": "%s", "status": "RJCT", "reasonCode": "%s"}
        """
            .formatted(endToEndId.value(), reasonCode));
  }

  /** Sends the body with a correct signature. */
  public RestTestClient.ResponseSpec send(String body) {
    return sendSigned(body, sign(body, key));
  }

  /** Sends the body with the signature given, right or wrong. */
  public RestTestClient.ResponseSpec sendSigned(String body, String signature) {
    return http.getObject()
        .post()
        .uri("/v1/scheme/callbacks")
        .contentType(MediaType.APPLICATION_JSON)
        .header(SIGNATURE_HEADER, signature)
        .body(body)
        .exchange();
  }

  /** HMAC-SHA256 of the body under the key, as lowercase hex. */
  public static String sign(String body, String key) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
