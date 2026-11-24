package com.paymentledger.seed;

import java.util.ArrayList;
import java.util.List;

/** Keeps everything the generator makes, for tests that read the seed data back. */
final class CollectedSeed implements SeedSink {

  final List<SeededCustomer> customers = new ArrayList<>();
  final List<SeededAccount> accounts = new ArrayList<>();

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
}
