package com.paymentledger.ledger.payments;

/** Where a Payment stands. Completed and Rejected are final. */
public enum PaymentStatus {
  PENDING,
  COMPLETED,
  REJECTED
}
