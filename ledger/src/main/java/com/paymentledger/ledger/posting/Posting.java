package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.TransactionId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Posts Transactions and keeps each Account's Balance. */
public interface Posting {

  /** Posts the Transaction, joining the caller's database transaction if there is one. */
  TransactionId post(NewTransaction transaction);

  /**
   * Locks the Accounts' balance rows, one at a time in ascending account id order, until the
   * caller's database transaction ends, and returns their Balances. Run a funds check against these
   * before posting. Needs a database transaction already in progress.
   */
  Map<AccountId, Money> lockBalances(Set<AccountId> accounts);

  Optional<PostedTransaction> find(TransactionId id);

  /** Whether a Reversal or Reversing Correction of the Transaction has been posted. */
  boolean isReversed(TransactionId transaction);

  /** The Account's Balance on its Normal Side, or empty for an unknown Account. */
  Optional<Money> balanceOf(AccountId account);

  /** The Transactions a Payment led to, oldest first. */
  List<TransactionId> transactionsOf(PaymentId payment);
}
