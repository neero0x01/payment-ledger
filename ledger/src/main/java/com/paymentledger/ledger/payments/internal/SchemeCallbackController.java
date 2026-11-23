package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.EndToEndId;
import com.paymentledger.ledger.payments.SchemeOutcome;
import com.paymentledger.ledger.payments.SchemeReasonCode;
import com.paymentledger.ledger.shared.Problem;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Receives Scheme Outcomes from the Faster Payments Scheme (ADR 0008). */
@RestController
class SchemeCallbackController {

  private final PaymentService payments;

  SchemeCallbackController(PaymentService payments) {
    this.payments = payments;
  }

  @PostMapping(path = "/{version}/scheme/callbacks", version = "1")
  void schemeOutcome(@RequestBody Pacs002Lite callback) {
    payments.recordSchemeOutcome(callback.toSchemeOutcome());
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
