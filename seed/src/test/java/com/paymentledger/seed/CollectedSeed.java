package com.paymentledger.seed;

import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.shared.AccountId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Keeps everything the generator makes, for tests that read the seed data back. */
final class CollectedSeed implements SeedSink {

  final List<SeededCustomer> customers = new ArrayList<>();
  final List<SeededAccount> accounts = new ArrayList<>();
  final List<SeededPayment> payments = new ArrayList<>();

  /** In the order the generator posted them. */
  final List<PostedTransaction> transactions = new ArrayList<>();

  final Map<AccountId, SeededBalance> balances = new HashMap<>();

  /** Generates seed data for the number of Customers and collects it. */
  static CollectedSeed generate(int customers) {
    CollectedSeed seed = new CollectedSeed();
    new SeedGenerator(customers, SeedFixtures.INTERNAL).generate(seed);
    return seed;
  }

  @Override
  public void customer(SeededCustomer customer) {
    customers.add(customer);
  }

  @Override
  public void account(SeededAccount account) {
    accounts.add(account);
  }

  @Override
  public void payment(SeededPayment payment) {
    payments.add(payment);
  }

  @Override
  public void transaction(PostedTransaction transaction, UUID eventId) {
    transactions.add(transaction);
  }

  @Override
  public void balance(SeededBalance balance) {
    balances.put(balance.account(), balance);
  }
}
