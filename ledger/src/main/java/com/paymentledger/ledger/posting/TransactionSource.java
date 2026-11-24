package com.paymentledger.ledger.posting;

/**
 * Where a Transaction came from: posted by the running ledger, or bulk-loaded by the seed
 * generator, the only path allowed to book a Transaction in the past.
 */
public enum TransactionSource {
  LIVE,
  SEED
}
