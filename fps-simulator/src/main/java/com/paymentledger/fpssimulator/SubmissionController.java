package com.paymentledger.fpssimulator;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Takes Scheme Submissions from the ledger. Answers 202 at once; the Scheme Outcome follows later
 * as a callback to the ledger. A submission it can't read is refused as a 400 problem.
 */
@RestController
class SubmissionController {

  /** The longest End-to-End Id ISO 20022 allows. */
  private static final int MAX_END_TO_END_ID = 35;

  private final Scheme scheme;

  SubmissionController(Scheme scheme) {
    this.scheme = scheme;
  }

  @PostMapping("/scheme/v1/submissions")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void submit(@RequestBody Pacs008Lite submission) {
    String endToEndId = submission.endToEndId();
    if (endToEndId == null || endToEndId.isBlank() || endToEndId.length() > MAX_END_TO_END_ID) {
      throw badRequest("A Scheme Submission needs an End-to-End Id of up to 35 characters");
    }
    if (submission.creditorAccountNumber() == null || submission.creditorSortCode() == null) {
      throw badRequest("A Scheme Submission needs the creditor's sort code and account number");
    }
    if (submission.amountPence() <= 0) {
      throw badRequest("A Scheme Submission's amount must be more than zero");
    }
    scheme.receive(submission);
  }

  private static ResponseStatusException badRequest(String detail) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
  }
}
