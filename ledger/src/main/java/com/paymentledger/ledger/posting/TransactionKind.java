package com.paymentledger.ledger.posting;

/** Why a Transaction was posted. */
public enum TransactionKind {
  PAYMENT,
  REVERSAL,
  FUNDING,
  CORRECTION
}
