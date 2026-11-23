package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.EndToEndId;
import com.paymentledger.ledger.payments.SchemeOutcome;
import com.paymentledger.ledger.payments.SchemeReasonCode;
import com.paymentledger.ledger.shared.Problem;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives Scheme Outcomes from the Faster Payments Scheme (ADR 0008). The scheme has no session:
 * each callback carries the HMAC-SHA256 of its raw body under the shared key, as hex, in the {@code
 * Scheme-Signature} header. A callback without a correct signature is refused as unauthenticated
 * before it is read.
 */
@RestController
class SchemeCallbackController {

  static final String SIGNATURE_HEADER = "Scheme-Signature";
  private static final String HMAC = "HmacSHA256";

  private final PaymentService payments;
  private final JsonMapper json;
  private final SecretKeySpec key;

  SchemeCallbackController(
      PaymentService payments,
      JsonMapper json,
      @Value("${ledger.scheme.callback-hmac-key}") String key) {
    this.payments = payments;
    this.json = json;
    this.key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC);
  }

  @PostMapping(path = "/{version}/scheme/callbacks", version = "1")
  void schemeOutcome(
      @RequestHeader(name = SIGNATURE_HEADER, required = false) @Nullable String signature,
      @RequestBody byte[] body) {
    requireSignedByTheScheme(body, signature);
    @Nullable Pacs002Lite callback;
    try {
      callback = json.readValue(body, Pacs002Lite.class);
    } catch (JacksonException e) {
      throw Problem.validationFailed("A Scheme Outcome is a pacs.002-lite JSON object");
    }
    if (callback == null) {
      // The JSON literal null reads as no object at all.
      throw Problem.validationFailed("A Scheme Outcome is a pacs.002-lite JSON object");
    }
    payments.recordSchemeOutcome(callback.toSchemeOutcome());
  }

  private void requireSignedByTheScheme(byte[] body, @Nullable String signature) {
    byte[] expected = HexFormat.of().formatHex(hmac(body)).getBytes(StandardCharsets.US_ASCII);
    byte[] sent =
        signature == null
            ? new byte[0]
            : signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
    if (!MessageDigest.isEqual(expected, sent)) {
      throw Problem.unauthenticated("The Scheme Outcome's signature is missing or wrong");
    }
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

  /**
   * A slimmed-down ISO 20022 pacs.002: the End-to-End Id, ACSC or RJCT, and the reason code of an
   * RJCT.
   */
  record Pacs002Lite(
      @Nullable String endToEndId, @Nullable String status, @Nullable String reasonCode) {

    SchemeOutcome toSchemeOutcome() {
      if (endToEndId == null || endToEndId.isBlank() || endToEndId.length() > 35) {
        throw Problem.validationFailed("A Scheme Outcome needs an End-to-End Id");
      }
      SchemeOutcome.Status parsed;
      try {
        parsed = SchemeOutcome.Status.valueOf(String.valueOf(status));
      } catch (IllegalArgumentException e) {
        throw Problem.validationFailed("A Scheme Outcome's status is ACSC or RJCT");
      }
      if ((parsed == SchemeOutcome.Status.RJCT) != (reasonCode != null)) {
        throw Problem.validationFailed("An RJCT Scheme Outcome, and only one, has a reason code");
      }
      SchemeReasonCode code;
      try {
        code = reasonCode == null ? null : SchemeReasonCode.valueOf(reasonCode);
      } catch (IllegalArgumentException e) {
        throw Problem.validationFailed("A scheme reason code is AC01, AC04, AG01 or MS03");
      }
      return new SchemeOutcome(new EndToEndId(endToEndId), parsed, code);
    }
  }
}
