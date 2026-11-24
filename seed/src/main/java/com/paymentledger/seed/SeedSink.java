package com.paymentledger.seed;

import com.paymentledger.ledger.posting.PostedTransaction;
import java.util.UUID;

/**
 * Receives the seed data as the generator makes it: parties first, then Payments and Transactions
 * in booking order, then the balance row of every Account with an Entry.
 */
public interface SeedSink {

  void customer(SeededCustomer customer);

  void account(SeededAccount account);

  void payment(SeededPayment payment);

  /** A posted Transaction, and the eventId of the TransactionPosted event that announces it. */
  void transaction(PostedTransaction transaction, UUID eventId);

  void balance(SeededBalance balance);
}
