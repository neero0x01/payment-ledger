package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.TransactionId;
import java.util.List;
import java.util.Optional;

/** Posts Transactions and keeps each Account's Balance. */
public interface Posting {

  /** Posts the Transaction, joining the caller's database transaction if there is one. */
  TransactionId post(NewTransaction transaction);

  Optional<PostedTransaction> find(TransactionId id);

  /** The Transactions a Payment led to, oldest first. */
  List<TransactionId> transactionsOf(PaymentId payment);
}
