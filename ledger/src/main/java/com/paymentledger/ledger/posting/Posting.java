package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.TransactionId;
import java.util.List;
import java.util.Optional;

/** Posts Transactions and keeps each Account's Balance. */
public interface Posting {

  /** Posts the Transaction, joining the caller's database transaction if there is one. */
  TransactionId post(NewTransaction transaction);

  Optional<PostedTransaction> find(TransactionId id);

  /** Whether a Reversal or Reversing Correction of the Transaction has been posted. */
  boolean isReversed(TransactionId transaction);

  /** The Account's Balance on its Normal Side, or empty for an unknown Account. */
  Optional<Money> balanceOf(AccountId account);

  /** The Transactions a Payment led to, oldest first. */
  List<TransactionId> transactionsOf(PaymentId payment);
}
