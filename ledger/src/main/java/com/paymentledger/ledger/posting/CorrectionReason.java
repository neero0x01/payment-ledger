package com.paymentledger.ledger.posting;

/** Why a Correction was posted. */
public enum CorrectionReason {
  DUPLICATE_POSTING,
  WRONG_AMOUNT,
  WRONG_ACCOUNT,
  GOODWILL,
  OTHER
}
