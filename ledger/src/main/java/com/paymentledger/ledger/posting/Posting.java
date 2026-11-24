package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.IdempotentOutcome;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.TransactionId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Posts Transactions and keeps each Account's Balance. */
public interface Posting {

  /** Posts the Transaction, joining the caller's database transaction if there is one. */
  TransactionId post(NewTransaction transaction);

  /**
   * Posts the Correction once per Operator and Idempotency-Key. When a request with the same key
   * posted one first, waits for it to commit and replays it instead, refusing a different request
   * as idempotency-key-reused (ADR 0007). Joins the caller's database transaction.
   */
  IdempotentOutcome<TransactionId> postCorrection(NewTransaction correction, KeyedCorrection keyed);

  /**
   * The Correction the Operator already posted with the Idempotency-Key, or empty when the key is
   * unused. Refuses a different request sent with a used key as idempotency-key-reused.
   */
  Optional<TransactionId> correctionPostedWith(KeyedCorrection keyed);

  /**
   * Locks the Accounts' balance rows, one at a time in ascending account id order, until the
   * caller's database transaction ends, and returns their Balances. Run a funds check against these
   * before posting. Needs a database transaction already in progress.
   */
  Map<AccountId, Money> lockBalances(Set<AccountId> accounts);

  Optional<PostedTransaction> find(TransactionId id);

  /** Whether a Reversal or Reversing Correction of the Transaction has been posted. */
  boolean isReversed(TransactionId transaction);

  /** The Reversal or Reversing Correction of the Transaction, or empty while it is not reversed. */
  Optional<TransactionId> reversalOf(TransactionId transaction);

  /**
   * Every Correction of the Transaction, Reversing Corrections and Adjustments alike, in the order
   * they were booked.
   */
  List<TransactionId> correctionsOf(TransactionId transaction);

  /** The Account's Balance on its Normal Side, or empty for an unknown Account. */
  Optional<Money> balanceOf(AccountId account);

  /**
   * The Account's As-At Balance: its balance_after at the latest Entry booked at or before the
   * moment, taking the highest entry_seq when Booking Times tie, or zero before its first Entry.
   * Empty for an unknown Account. Refuses a moment in the future as validation-failed.
   */
  Optional<Money> balanceOf(AccountId account, Instant asAt);

  /** The Transactions a Payment led to, oldest first. */
  List<TransactionId> transactionsOf(PaymentId payment);
}
