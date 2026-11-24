package com.paymentledger.ledger.shared.internal;

import com.paymentledger.ledger.shared.Problem;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers every {@link Problem} as an RFC 9457 problem detail of its type. A problem about one
 * request field names it in the {@code field} extension member.
 */
@RestControllerAdvice
class ProblemHandler {

  @ExceptionHandler(Problem.class)
  ResponseEntity<ProblemDetail> problem(Problem problem) {
    HttpStatus status = HttpStatus.valueOf(problem.type().status());
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, problem.detail());
    body.setType(problem.type().uri());
    body.setTitle(problem.type().title());
    problem.field().ifPresent(field -> body.setProperty("field", field));
    return ResponseEntity.status(status).body(body);
  }
}
