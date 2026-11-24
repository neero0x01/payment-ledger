package com.paymentledger.seed;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.accounts.AccountKind;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import net.datafaker.Faker;
import org.jspecify.annotations.Nullable;

/**
 * Generates the seed data (spec §10): synthetic Customers with their Customer Accounts, and the
 * load-test Accounts. Every draw comes from a fixed random seed, so every run generates identical
 * data.
 */
public final class SeedGenerator {

  /** The fixed random seed every run starts from. */
  static final long RANDOM_SEED = 20251101L;

  /** How many load-test Accounts the seed opens, whatever the number of Customers. */
  static final int LOAD_TEST_ACCOUNTS = 200;

  /** Customers open their Accounts during the month before the first seeded Transaction. */
  private static final Instant OPENING_FROM = Instant.parse("2025-10-01T00:00:00Z");

  private static final long OPENING_SECONDS = 30L * 24 * 60 * 60;

  /** About 3 in 10 Customers also hold a Savings Account: about 6,500 Accounts for 5,000. */
  private static final double SAVINGS_SHARE = 0.3;

  /**
   * The share of Current Accounts with an Overdraft Limit, so that about 1 in 10 of all Customer
   * Accounts has one (Savings Accounts never do).
   */
  private static final double OVERDRAFT_SHARE = 0.13;

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
    List<Holder> holders = new ArrayList<>();
    for (int i = 0; i < customers; i++) {
      holders.add(openCustomer(sink));
    }
    for (int i = 1; i <= LOAD_TEST_ACCOUNTS; i++) {
      openLoadTestAccount(sink, i);
    }
  }

  private Holder openCustomer(SeedSink sink) {
    Instant openedAt = OPENING_FROM.plus(random.between(0, OPENING_SECONDS), ChronoUnit.SECONDS);
    CustomerId customer = new CustomerId(random.uuidV7(openedAt));
    sink.customer(new SeededCustomer(customer, faker.name().fullName(), openedAt));
    Money overdraftLimit =
        random.chance(OVERDRAFT_SHARE)
            ? Money.ofPence(random.between(1, 8) * 250_00)
            : Money.ofPence(0);
    Account current = open(sink, customer, "Current Account", overdraftLimit, false, openedAt);
    Account savings =
        random.chance(SAVINGS_SHARE)
            ? open(sink, customer, "Savings Account", Money.ofPence(0), false, openedAt)
            : null;
    return new Holder(customer, current, savings);
  }

  private void openLoadTestAccount(SeedSink sink, int number) {
    String suffix = "%03d".formatted(number);
    Instant openedAt = OPENING_FROM.plus(OPENING_SECONDS, ChronoUnit.SECONDS);
    CustomerId customer = new CustomerId(random.uuidV7(openedAt));
    sink.customer(new SeededCustomer(customer, "Load Test Customer " + suffix, openedAt));
    open(sink, customer, "Load Test Account " + suffix, Money.ofPence(0), true, openedAt);
  }

  private Account open(
      SeedSink sink,
      CustomerId owner,
      String name,
      Money overdraftLimit,
      boolean loadTest,
      Instant openedAt) {
    Account account =
        new Account(
            new AccountId(random.uuidV7(openedAt)),
            name,
            AccountKind.CUSTOMER,
            Side.CREDIT,
            overdraftLimit,
            owner);
    sink.account(
        new SeededAccount(
            account, SortCodes.FINTECH, String.valueOf(nextAccountNumber++), loadTest, openedAt));
    return account;
  }

  /** A Customer and their Customer Accounts: always a Current Account, sometimes Savings. */
  private record Holder(CustomerId customer, Account current, @Nullable Account savings) {}
}
