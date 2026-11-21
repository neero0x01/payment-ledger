package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.shared.ProblemType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Correction requests the ledger refuses, answered as RFC 9457 problems. */
final class CorrectionProblems {

  private CorrectionProblems() {}

  static ErrorResponseException notFound(String detail) {
    return problem(HttpStatus.NOT_FOUND, ProblemType.NOT_FOUND, detail);
  }

  static ErrorResponseException alreadyReversed(String detail) {
    return problem(HttpStatus.CONFLICT, ProblemType.ALREADY_REVERSED, detail);
  }

  private static ErrorResponseException problem(
      HttpStatus status, ProblemType type, String detail) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setType(type.uri());
    body.setTitle(type.title());
    return new ErrorResponseException(status, body, null);
  }
}
