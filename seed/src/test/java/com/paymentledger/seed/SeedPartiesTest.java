package com.paymentledger.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Spec §10: the seed's Customers and Customer Accounts. At full size that is 5,000 Customers with
 * about 6,500 Customer Accounts, plus 200 load-test Accounts; here a smaller run keeps the same
 * proportions.
 */
class SeedPartiesTest {

  private static final int CUSTOMERS = 1_000;

  private static CollectedSeed seed;

  @BeforeAll
  static void generate() {
    seed = CollectedSeed.generate(CUSTOMERS);
  }

  @Test
  void about_thirteen_customer_accounts_for_every_ten_customers() {
    List<SeededAccount> customerAccounts = customerAccounts();

    assertThat(customerAccounts).hasSizeBetween(1_250, 1_350);
    assertThat(seed.customers)
        .filteredOn(customer -> !isLoadTestCustomer(customer))
        .hasSize(CUSTOMERS);
  }

  @Test
  void two_hundred_load_test_accounts_whatever_the_size() {
    assertThat(seed.accounts).filteredOn(SeededAccount::loadTest).hasSize(200);
  }

  @Test
  void about_one_in_ten_customer_accounts_has_an_overdraft_limit() {
    List<SeededAccount> customerAccounts = customerAccounts();

    long withOverdraft =
        customerAccounts.stream()
            .filter(account -> account.account().overdraftLimit().isPositive())
            .count();

    assertThat((double) withOverdraft / customerAccounts.size()).isBetween(0.08, 0.12);
  }

  @Test
  void customer_names_come_from_datafaker() {
    List<String> names =
        seed.customers.stream()
            .filter(customer -> !isLoadTestCustomer(customer))
            .map(SeededCustomer::displayName)
            .toList();

    assertThat(names).allMatch(name -> name.matches("\\p{L}[\\p{L}' .-]* [\\p{L}'.-]+"));
    assertThat(names.stream().distinct().count()).isGreaterThan(950L);
  }

  @Test
  void every_account_has_a_fictional_sort_code_and_its_own_account_number() {
    assertThat(seed.accounts)
        .allMatch(account -> isFictional(account.sortCode()), "a fictional sort code")
        .extracting(SeededAccount::accountNumber)
        .allMatch(number -> number.matches("[0-9]{8}"), "eight digits")
        .doesNotHaveDuplicates();
  }

  private static List<SeededAccount> customerAccounts() {
    return seed.accounts.stream().filter(account -> !account.loadTest()).toList();
  }

  private static boolean isLoadTestCustomer(SeededCustomer customer) {
    return seed.accounts.stream()
        .anyMatch(account -> account.loadTest() && account.account().isOwnedBy(customer.id()));
  }

  private static boolean isFictional(String sortCode) {
    return sortCode.compareTo(SortCodes.FICTIONAL_FROM) >= 0
        && sortCode.compareTo(SortCodes.FICTIONAL_TO) <= 0
        && sortCode.matches("[0-9]{6}");
  }
}
