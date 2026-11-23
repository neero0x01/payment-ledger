package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.PaymentScheme;
import com.paymentledger.ledger.payments.SchemeSubmission;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The PaymentScheme port over HTTP (ADR 0008): posts a pacs.008-lite to the simulator's {@code POST
 * /scheme/v1/submissions}, which answers 202 and calls back later.
 */
@Component
class HttpPaymentScheme implements PaymentScheme {

  private final RestClient http;

  HttpPaymentScheme(@Value("${ledger.scheme.base-url}") String baseUrl) {
    this.http = RestClient.create(baseUrl);
  }

  @Override
  public void submit(SchemeSubmission submission) {
    http.post()
        .uri("/scheme/v1/submissions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Pacs008Lite.of(submission))
        .retrieve()
        .toBodilessEntity();
  }

  /** The submission's JSON body. */
  record Pacs008Lite(
      String endToEndId,
      UUID debtorAccountId,
      String creditorSortCode,
      String creditorAccountNumber,
      String creditorName,
      long amountPence) {

    static Pacs008Lite of(SchemeSubmission submission) {
      return new Pacs008Lite(
          submission.endToEndId().value(),
          submission.debtorAccount().value(),
          submission.creditor().sortCode(),
          submission.creditor().accountNumber(),
          submission.creditor().name(),
          submission.amount().pence());
    }
  }
}
