package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.TransactionId;

/** Posts Transactions and keeps each Account's Balance. */
public interface Posting {

  /** Posts the Transaction, joining the caller's database transaction if there is one. */
  TransactionId post(NewTransaction transaction);
}
