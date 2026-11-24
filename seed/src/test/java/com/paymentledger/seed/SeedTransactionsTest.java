package com.paymentledger.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.payments.Payee;
import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.PaymentStatus;
import com.paymentledger.ledger.payments.PaymentType;
import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.PostedEntry;
import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.posting.TransactionKind;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.Side;
import com.paymentledger.ledger.shared.TransactionId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Spec §10: the seed's Transactions, booked from 2025-11-01 to 2026-10-31, about 200 for every
 * Customer (about 1M for 5,000), in the patterns a small fintech sees. A smaller run keeps the same
 * proportions.
 */
class SeedTransactionsTest {

  private static final int CUSTOMERS = 300;
  private static final Instant FIRST_BOOKING = Instant.parse("2025-11-01T00:00:00Z");
  private static final Instant AFTER_LAST_BOOKING = Instant.parse("2026-11-01T00:00:00Z");

  private static CollectedSeed seed;
  private static Map<AccountId, Account> accounts;

  @BeforeAll
  static void generate() {
    seed = CollectedSeed.generate(CUSTOMERS);
    accounts =
        seed.accounts.stream()
            .map(SeededAccount::account)
            .collect(Collectors.toMap(Account::id, Function.identity()));
  }

  @Test
  void about_two_hundred_transactions_a_customer_booked_over_twelve_months() {
    assertThat(seed.transactions)
        .allMatch(t -> !t.bookingTime().isBefore(FIRST_BOOKING), "booked from 2025-11-01")
        .allMatch(t -> t.bookingTime().isBefore(AFTER_LAST_BOOKING), "booked to 2026-10-31");
    assertThat(seed.transactions).hasSizeBetween(180 * CUSTOMERS, 220 * CUSTOMERS);
  }

  @Test
  void every_current_account_is_funded_from_funding_every_month() {
    List<Account> currentAccounts =
        seed.accounts.stream()
            .filter(a -> !a.loadTest() && a.account().name().equals("Current Account"))
            .map(SeededAccount::account)
            .toList();
    Map<AccountId, Set<YearMonth>> fundedMonths = new HashMap<>();
    for (PostedTransaction t : seed.transactions) {
      if (t.kind() == TransactionKind.FUNDING && postsTo(t, SeedFixtures.INTERNAL.funding())) {
        for (Entry entry : t.entries()) {
          fundedMonths
              .computeIfAbsent(entry.account(), id -> new java.util.HashSet<>())
              .add(YearMonth.from(t.bookingTime().atZone(ZoneOffset.UTC)));
        }
      }
    }

    assertThat(currentAccounts)
        .allMatch(account -> fundedMonths.getOrDefault(account.id(), Set.of()).size() == 12);
  }

  @Test
  void every_load_test_account_is_funded_with_a_large_balance() {
    assertThat(seed.accounts)
        .filteredOn(SeededAccount::loadTest)
        .allMatch(account -> finalBalanceOf(account.account().id()) >= 100_000_00);
  }

  @Test
  void customers_make_faster_payments_every_day() {
    Set<LocalDate> days =
        seed.payments.stream()
            .filter(p -> p.payment().type() == PaymentType.FASTER_PAYMENT)
            .map(p -> LocalDate.ofInstant(p.createdAt(), ZoneOffset.UTC))
            .collect(Collectors.toSet());

    assertThat(days).hasSize(365);
    assertThat(seed.payments)
        .filteredOn(p -> p.payment().type() == PaymentType.FASTER_PAYMENT)
        .allMatch(p ->
            p.payment().payee() instanceof Payee.AtAnotherBank payee
                && payee.sortCode().compareTo(SortCodes.FICTIONAL_TO) <= 0
                && !payee.sortCode().equals(SortCodes.FINTECH));
  }

  @Test
  void customers_transfer_between_their_own_accounts() {
    long ownAccountTransfers =
        seed.payments.stream()
            .filter(p -> p.payment().type() == PaymentType.INTERNAL_TRANSFER)
            .filter(p ->
                p.payment().payee() instanceof Payee.AtTheFintech payee
                    && accounts
                        .get(payee.account())
                        .owner()
                        .equals(accounts.get(p.payment().debtorAccount()).owner()))
            .count();

    assertThat(ownAccountTransfers).isGreaterThan(CUSTOMERS);
  }

  @Test
  void overdrafts_are_used_but_no_payment_goes_past_the_overdraft_limit() {
    long overdrawnAccounts =
        seed.transactions.stream()
            .flatMap(t -> t.postedEntries().stream())
            .filter(e -> accounts.containsKey(e.entry().account()))
            .filter(e -> e.balanceAfter().pence() < 0)
            .map(e -> e.entry().account())
            .distinct()
            .count();
    assertThat(overdrawnAccounts).isPositive();

    for (PostedTransaction t : seed.transactions) {
      if (t.kind() == TransactionKind.PAYMENT) {
        PostedEntry debtor =
            t.postedEntries().stream()
                .filter(e -> e.entry().side() == Side.DEBIT)
                .findFirst()
                .orElseThrow();
        Account account = accounts.get(debtor.entry().account());
        assertThat(debtor.balanceAfter().plus(account.overdraftLimit()).pence())
            .as("Available Balance after %s", t.id())
            .isNotNegative();
      }
    }
  }

  @Test
  void about_two_in_a_hundred_faster_payments_are_scheme_rejected_and_reversed_once() {
    List<SeededPayment> fasterPayments =
        seed.payments.stream()
            .filter(p -> p.payment().type() == PaymentType.FASTER_PAYMENT)
            .filter(p -> p.payment().rejection().orElse(null) != RejectionReason.INSUFFICIENT_FUNDS)
            .toList();
    List<SeededPayment> schemeRejected =
        fasterPayments.stream()
            .filter(p -> p.payment().rejection().orElse(null) == RejectionReason.SCHEME_REJECTED)
            .toList();

    assertThat((double) schemeRejected.size() / fasterPayments.size()).isBetween(0.015, 0.025);
    Map<PaymentId, List<PostedTransaction>> byPayment =
        seed.transactions.stream()
            .filter(t -> t.payment().isPresent())
            .collect(Collectors.groupingBy(t -> t.payment().orElseThrow()));
    for (SeededPayment rejected : schemeRejected) {
      assertThat(rejected.payment().schemeRejection()).isPresent();
      List<PostedTransaction> transactions = byPayment.get(rejected.payment().id());
      assertThat(transactions)
          .extracting(PostedTransaction::kind)
          .containsExactly(TransactionKind.PAYMENT, TransactionKind.REVERSAL);
      PostedTransaction original = transactions.get(0);
      PostedTransaction reversal = transactions.get(1);
      assertThat(reversal.reverses()).contains(original.id());
      assertThat(reversal.entries())
          .containsExactlyElementsOf(original.entries().stream().map(Entry::mirrored).toList());
    }
  }

  @Test
  void every_completed_payment_has_one_transaction_and_a_rejected_one_for_funds_has_none() {
    Map<PaymentId, Long> transactionsByPayment =
        seed.transactions.stream()
            .filter(t -> t.payment().isPresent())
            .collect(Collectors.groupingBy(t -> t.payment().orElseThrow(), Collectors.counting()));

    assertThat(seed.payments).noneMatch(p -> p.payment().status() == PaymentStatus.PENDING);
    for (SeededPayment seeded : seed.payments) {
      Payment payment = seeded.payment();
      long expected =
          switch (payment.status()) {
            case COMPLETED -> 1;
            case REJECTED ->
                payment.rejection().orElseThrow() == RejectionReason.SCHEME_REJECTED ? 2 : 0;
            case PENDING -> throw new AssertionError();
          };
      assertThat(transactionsByPayment.getOrDefault(payment.id(), 0L))
          .as("Transactions of %s", payment)
          .isEqualTo(expected);
    }
  }

  @Test
  void operators_post_a_few_corrections_for_every_hundred_customers() {
    List<PostedTransaction> corrections =
        seed.transactions.stream().filter(t -> t.kind() == TransactionKind.CORRECTION).toList();

    // A few hundred for 5,000 Customers.
    assertThat(corrections).hasSizeBetween(CUSTOMERS * 4 / 100, CUSTOMERS * 8 / 100);
    assertThat(corrections).allMatch(t -> t.correction().isPresent());
    Map<TransactionId, PostedTransaction> byId =
        seed.transactions.stream()
            .collect(Collectors.toMap(PostedTransaction::id, Function.identity()));
    List<PostedTransaction> reversing =
        corrections.stream().filter(t -> t.reverses().isPresent()).toList();
    assertThat(reversing).isNotEmpty();
    for (PostedTransaction correction : reversing) {
      PostedTransaction original = byId.get(correction.reverses().orElseThrow());
      assertThat(original.bookingTime()).isBefore(correction.bookingTime());
      assertThat(correction.entries())
          .containsExactlyElementsOf(original.entries().stream().map(Entry::mirrored).toList());
    }
    assertThat(seed.transactions)
        .filteredOn(t -> t.reverses().isPresent())
        .extracting(t -> t.reverses().orElseThrow())
        .doesNotHaveDuplicates();
    assertThat(corrections).anyMatch(t -> t.reverses().isEmpty());
  }

  @Test
  void every_account_numbers_its_entries_in_booking_order_with_the_balance_after_each() {
    Map<AccountId, Long> lastSeq = new HashMap<>();
    Map<AccountId, Long> balance = new HashMap<>();
    Map<AccountId, Instant> lastBooking = new HashMap<>();
    for (PostedTransaction t : seed.transactions) {
      for (PostedEntry posted : t.postedEntries()) {
        AccountId account = posted.entry().account();
        Side normalSide =
            accounts.containsKey(account) ? accounts.get(account).normalSide() : Side.DEBIT;
        long expectedBalance =
            balance.getOrDefault(account, 0L) + posted.entry().balanceChange(normalSide).pence();
        assertThat(posted.entrySeq()).isEqualTo(lastSeq.getOrDefault(account, 0L) + 1);
        assertThat(posted.balanceAfter().pence()).isEqualTo(expectedBalance);
        assertThat(t.bookingTime())
            .isAfterOrEqualTo(lastBooking.getOrDefault(account, t.bookingTime()));
        lastSeq.put(account, posted.entrySeq());
        balance.put(account, expectedBalance);
        lastBooking.put(account, t.bookingTime());
      }
    }

    assertThat(seed.balances.keySet()).isEqualTo(lastSeq.keySet());
    seed.balances.forEach((account, row) -> {
      assertThat(row.balance().pence()).isEqualTo(balance.get(account));
      assertThat(row.lastEntrySeq()).isEqualTo(lastSeq.get(account));
    });
  }

  private static boolean postsTo(PostedTransaction t, AccountId account) {
    return t.entries().stream().anyMatch(e -> e.account().equals(account));
  }

  private static long finalBalanceOf(AccountId account) {
    return seed.balances.get(account).balance().pence();
  }
}
