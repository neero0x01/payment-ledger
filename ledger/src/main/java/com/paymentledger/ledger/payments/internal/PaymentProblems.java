package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.shared.ProblemType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Broken Payment requests, answered as RFC 9457 problems before anything is stored. */
final class PaymentProblems {

  private PaymentProblems() {}

  static ErrorResponseException validationFailed(String detail) {
    return problem(HttpStatus.BAD_REQUEST, ProblemType.VALIDATION_FAILED, detail);
  }

  private static ErrorResponseException problem(
      HttpStatus status, ProblemType type, String detail) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setType(type.uri());
    body.setTitle(type.title());
    return new ErrorResponseException(status, body, null);
  }
}
