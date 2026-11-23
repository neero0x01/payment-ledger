package com.paymentledger.ledger.payments;

/**
 * The Faster Payments Scheme, as the ledger sees it (ADR 0008). The scheme answers a Scheme
 * Submission later, with a signed Scheme Outcome sent to the ledger's callback endpoint. Never
 * called inside a database transaction: the dispatcher submits Faster Payments after they commit.
 * Production uses an HTTP adapter; tests use an in-memory fake.
 */
public interface PaymentScheme {

  /**
   * Sends the Faster Payment to the scheme for a decision. The scheme dedupes on the End-to-End Id,
   * so sending the same submission again is safe. Throws if the scheme can't be reached.
   */
  void submit(SchemeSubmission submission);
}
