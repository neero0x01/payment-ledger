package com.paymentledger.seed;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.accounts.AccountKind;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import net.datafaker.Faker;

/**
 * Generates the seed data: synthetic Customers and their Customer Accounts. Every draw comes from a
 * fixed random seed, so every run generates identical data.
 */
public final class SeedGenerator {

  /** The fixed random seed every run starts from. */
  static final long RANDOM_SEED = 20251101L;

  /** Customers open their Accounts during the month before the first seeded Transaction. */
  private static final Instant OPENING_FROM = Instant.parse("2025-10-01T00:00:00Z");

  private static final long OPENING_SECONDS = 30L * 24 * 60 * 60;

  private final int customers;
  private final SeedRandom random = new SeedRandom(RANDOM_SEED);
  private final Faker faker = new Faker(Locale.UK, new Random(RANDOM_SEED));
  private long nextAccountNumber = 10_000_001L;

  public SeedGenerator(int customers, InternalAccounts internal) {
    if (customers < 1) {
      throw new IllegalArgumentException("The seed needs at least one Customer, was " + customers);
    }
    Objects.requireNonNull(internal, "internal");
    this.customers = customers;
  }

  public void generate(SeedSink sink) {
    for (int i = 0; i < customers; i++) {
      Instant openedAt = OPENING_FROM.plus(random.between(0, OPENING_SECONDS), ChronoUnit.SECONDS);
      CustomerId customer = new CustomerId(random.uuidV7(openedAt));
      sink.customer(new SeededCustomer(customer, faker.name().fullName(), openedAt));
      Account current =
          new Account(
              new AccountId(random.uuidV7(openedAt)),
              "Current Account",
              AccountKind.CUSTOMER,
              Side.CREDIT,
              Money.ofPence(0),
              customer);
      sink.account(
          new SeededAccount(
              current, SortCodes.FINTECH, String.valueOf(nextAccountNumber++), false, openedAt));
    }
  }
}
