package com.paymentledger.fpssimulator;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Takes Scheme Submissions from the ledger. Answers 202 at once; the Scheme Outcome follows later
 * as a callback to the ledger.
 */
@RestController
class SubmissionController {

  private final Scheme scheme;

  SubmissionController(Scheme scheme) {
    this.scheme = scheme;
  }

  @PostMapping("/scheme/v1/submissions")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void submit(@RequestBody Pacs008Lite submission) {
    scheme.receive(submission);
  }
}
