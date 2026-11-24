package com.paymentledger.seed;

/** Receives the seed data as the generator makes it. */
public interface SeedSink {

  void customer(SeededCustomer customer);

  void account(SeededAccount account);
}
