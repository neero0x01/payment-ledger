package com.paymentledger.seed;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.accounts.AccountKind;
import com.paymentledger.ledger.payments.EndToEndId;
import com.paymentledger.ledger.payments.Payee;
import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.PaymentStatus;
import com.paymentledger.ledger.payments.PaymentType;
import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.payments.SchemeReasonCode;
import com.paymentledger.ledger.posting.CorrectionDetails;
import com.paymentledger.ledger.posting.CorrectionReason;
import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.Side;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Random;
import net.datafaker.Faker;
import org.jspecify.annotations.Nullable;

/**
 * Generates the seed data (spec §10): synthetic Customers with their Customer Accounts, the
 * load-test Accounts, and about 200 Transactions a Customer booked from 2025-11-01 to 2026-10-31.
 * Every draw comes from a fixed random seed, so every run generates identical data.
 *
 * <p>Each Customer is paid monthly from Funding into their Current Account, pays rent on the 1st
 * and spends by Faster Payments most days; Savings holders move money between their own Accounts.
 * Every Payment goes through the funds check, about 2% of Faster Payments meet a Scheme Rejection
 * and are reversed seconds later, and Operators post a few hundred Corrections: goodwill
 * Adjustments, and Reversing Corrections of funding posted twice.
 */
public final class SeedGenerator {

  /** The fixed random seed every run starts from. */
  static final long RANDOM_SEED = 20251101L;

  /** How many load-test Accounts the seed opens, whatever the number of Customers. */
  static final int LOAD_TEST_ACCOUNTS = 200;

  /** The first and last days of Booking Time (UTC). */
  static final LocalDate FIRST_DAY = LocalDate.of(2025, 11, 1);

  static final LocalDate LAST_DAY = LocalDate.of(2026, 10, 31);

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

  /** The mean number of everyday Faster Payments a Customer makes a day. */
  private static final double SPENDS_A_DAY = 0.45;

  /** The mean everyday Faster Payment, as a share of the Customer's monthly pay. */
  private static final double SPEND_SHARE_OF_PAY = 0.042;

  /** The share of Faster Payments the scheme rejects. */
  private static final double SCHEME_REJECTION_SHARE = 0.02;

  /** The chance a Customer gets a Correction on any day: a few hundred a year for 5,000. */
  private static final double CORRECTION_CHANCE_A_DAY = 0.06 / 365;

  /** Each load-test Account starts with £1,000,000.00. */
  private static final Money LOAD_TEST_FUNDING = Money.ofPence(1_000_000_00L);

  private static final int PAYEES = 500;

  private static final List<SchemeReasonCode> SCHEME_REASON_CODES =
      List.of(SchemeReasonCode.values());

  private final int customers;
  private final InternalAccounts internal;
  private final SeedRandom random = new SeedRandom(RANDOM_SEED);
  private final Faker faker = new Faker(Locale.UK, new Random(RANDOM_SEED));
  private final PriorityQueue<Scheduled> schedule =
      new PriorityQueue<>(Comparator.comparing(Scheduled::at).thenComparingLong(Scheduled::order));
  private long nextAccountNumber = 10_000_001L;
  private long scheduled;
  private @Nullable SeedSink sink;
  private @Nullable SeedPosting posting;

  /** The moment of the scheduled action running now. */
  private @Nullable Instant now;

  public SeedGenerator(int customers, InternalAccounts internal) {
    if (customers < 1) {
      throw new IllegalArgumentException("The seed needs at least one Customer, was " + customers);
    }
    this.customers = customers;
    this.internal = Objects.requireNonNull(internal, "internal");
  }

  /** Generates the seed data into the sink. A generator runs once. */
  public void generate(SeedSink sink) {
    if (this.sink != null) {
      throw new IllegalStateException("A seed generator runs once");
    }
    this.sink = sink;
    this.posting = new SeedPosting(sink, random);
    posting.open(internalAccount(internal.funding(), "Funding"));
    posting.open(internalAccount(internal.fpsClearing(), "FPS Clearing"));
    posting.open(internalAccount(internal.corrections(), "Corrections"));

    List<Holder> holders = new ArrayList<>();
    for (int i = 0; i < customers; i++) {
      holders.add(openCustomer());
    }
    List<Account> loadTestAccounts = new ArrayList<>();
    for (int i = 1; i <= LOAD_TEST_ACCOUNTS; i++) {
      loadTestAccounts.add(openLoadTestAccount(i));
    }
    List<Payee.AtAnotherBank> payees = new ArrayList<>();
    for (int i = 0; i < PAYEES; i++) {
      payees.add(externalPayee(faker.company().name()));
    }

    Instant firstMoment = startOf(FIRST_DAY);
    for (Account account : loadTestAccounts) {
      at(firstMoment.plus(Duration.ofMinutes(1)), () -> fund(account.id(), LOAD_TEST_FUNDING));
    }
    for (LocalDate day = FIRST_DAY; !day.isAfter(LAST_DAY); day = day.plusDays(1)) {
      for (Holder holder : holders) {
        planDay(holder, day, payees);
      }
      runUntil(startOf(day.plusDays(1)));
    }
    if (!schedule.isEmpty()) {
      throw new IllegalStateException("Seed events scheduled after " + LAST_DAY);
    }
    posting.finish();
  }

  /** Schedules what the Customer does on the day. */
  private void planDay(Holder holder, LocalDate day, List<Payee.AtAnotherBank> payees) {
    Instant start = startOf(day);
    if (day.equals(FIRST_DAY)) {
      at(during(start, 0, 6), () -> fund(holder.current().id(), holder.pay()));
    }
    if (day.getDayOfMonth() == holder.payday()) {
      at(during(start, 6, 7), () -> fund(holder.current().id(), holder.pay()));
      Account savings = holder.savings();
      if (savings != null) {
        Money saved = Money.ofPence(holder.pay().pence() / 10);
        at(
            during(start, 12, 14),
            () -> internalTransfer(holder, holder.current(), savings.id(), saved, now()));
      }
    }
    if (day.getDayOfMonth() == 1) {
      at(during(start, 8, 9), () -> fasterPayment(holder, holder.landlord(), holder.rent()));
    }
    Account savings = holder.savings();
    if (savings != null && day.getDayOfMonth() == 15 && random.chance(0.3)) {
      at(
          during(start, 9, 21),
          () -> {
            long available = posting().balanceOf(savings.id()).pence();
            if (available > 100) {
              Money amount = Money.ofPence(random.between(available / 5, available * 4 / 5));
              internalTransfer(holder, savings, holder.current().id(), amount, now());
            }
          });
    }
    int spends = random.poisson(SPENDS_A_DAY);
    for (int i = 0; i < spends; i++) {
      Payee.AtAnotherBank payee = payees.get(random.below(payees.size()));
      long pence =
          Math.max(
              1_00,
              (long)
                  (holder.pay().pence()
                      * SPEND_SHARE_OF_PAY
                      * holder.spendFactor()
                      * random.exponential()));
      at(during(start, 7, 22), () -> fasterPayment(holder, payee, Money.ofPence(pence)));
    }
    if (random.chance(CORRECTION_CHANCE_A_DAY)) {
      if (random.chance(0.6) || day.isAfter(LAST_DAY.minusDays(7))) {
        Money goodwill = Money.ofPence(random.between(5, 50) * 100);
        at(during(start, 10, 17), () -> goodwill(holder.current().id(), goodwill));
      } else {
        at(during(start, 6, 7), () -> fundTwiceThenReverse(holder));
      }
    }
  }

  private PostedTransaction fund(AccountId account, Money amount) {
    return posting()
        .post(
            NewTransaction.funding(
                List.of(
                    new Entry(internal.funding(), Side.DEBIT, amount),
                    new Entry(account, Side.CREDIT, amount))),
            now());
  }

  /**
   * Inbound funding posted twice by mistake; an Operator finds it a day or three later and posts a
   * Reversing Correction of the duplicate.
   */
  private void fundTwiceThenReverse(Holder holder) {
    fund(holder.current().id(), holder.pay());
    at(
        now().plus(Duration.ofSeconds(random.between(1, 30))),
        () -> {
          PostedTransaction duplicate = fund(holder.current().id(), holder.pay());
          at(
              duringNextDays(now(), 1, 3),
              () ->
                  posting()
                      .post(
                          NewTransaction.reversingCorrection(
                              duplicate,
                              new CorrectionDetails(
                                  CorrectionReason.DUPLICATE_POSTING,
                                  "Inbound funding posted twice; reversing the duplicate")),
                          now()));
        });
  }

  private void goodwill(AccountId account, Money amount) {
    posting()
        .post(
            NewTransaction.adjustment(
                account,
                Side.CREDIT,
                amount,
                internal.corrections(),
                new CorrectionDetails(
                    CorrectionReason.GOODWILL, "Goodwill payment after a complaint")),
            now());
  }

  /** An Internal Transfer, Completed in the same moment, or Rejected for Insufficient Funds. */
  private void internalTransfer(
      Holder holder, Account debtor, AccountId payee, Money amount, Instant createdAt) {
    PaymentId id = new PaymentId(random.uuidV7(createdAt));
    if (!posting().canPayOut(debtor.id(), amount)) {
      rejectForFunds(
          holder,
          id,
          PaymentType.INTERNAL_TRANSFER,
          debtor,
          new Payee.AtTheFintech(payee),
          amount,
          createdAt,
          null);
      return;
    }
    posting()
        .post(
            NewTransaction.forPayment(
                id,
                List.of(
                    new Entry(debtor.id(), Side.DEBIT, amount),
                    new Entry(payee, Side.CREDIT, amount))),
            createdAt);
    sink()
        .payment(
            new SeededPayment(
                new Payment(
                    id,
                    PaymentType.INTERNAL_TRANSFER,
                    PaymentStatus.COMPLETED,
                    debtor.id(),
                    new Payee.AtTheFintech(payee),
                    amount,
                    null,
                    null),
                holder.customer(),
                createdAt,
                createdAt,
                null));
  }

  /**
   * A Faster Payment from the Customer's Current Account. Past the funds check its money waits in
   * FPS Clearing; the scheme accepts it within seconds, or rejects it and the ledger posts its
   * Reversal.
   */
  private void fasterPayment(Holder holder, Payee.AtAnotherBank payee, Money amount) {
    Instant createdAt = now();
    Account debtor = holder.current();
    PaymentId id = new PaymentId(random.uuidV7(createdAt));
    EndToEndId endToEndId = new EndToEndId(random.uuidV4().toString().replace("-", ""));
    if (!posting().canPayOut(debtor.id(), amount)) {
      rejectForFunds(
          holder, id, PaymentType.FASTER_PAYMENT, debtor, payee, amount, createdAt, endToEndId);
      return;
    }
    PostedTransaction original =
        posting()
            .post(
                NewTransaction.forPayment(
                    id,
                    List.of(
                        new Entry(debtor.id(), Side.DEBIT, amount),
                        new Entry(internal.fpsClearing(), Side.CREDIT, amount))),
                createdAt);
    Instant outcomeAt = createdAt.plus(Duration.ofMillis(random.between(200, 2_000)));
    if (random.chance(SCHEME_REJECTION_SHARE)) {
      SchemeReasonCode reason = SCHEME_REASON_CODES.get(random.below(SCHEME_REASON_CODES.size()));
      at(outcomeAt, () -> posting().post(NewTransaction.reversal(original), now()));
      sink()
          .payment(
              new SeededPayment(
                  new Payment(
                      id,
                      PaymentType.FASTER_PAYMENT,
                      PaymentStatus.REJECTED,
                      debtor.id(),
                      payee,
                      amount,
                      RejectionReason.SCHEME_REJECTED,
                      reason),
                  holder.customer(),
                  createdAt,
                  null,
                  endToEndId));
    } else {
      sink()
          .payment(
              new SeededPayment(
                  new Payment(
                      id,
                      PaymentType.FASTER_PAYMENT,
                      PaymentStatus.COMPLETED,
                      debtor.id(),
                      payee,
                      amount,
                      null,
                      null),
                  holder.customer(),
                  createdAt,
                  micros(outcomeAt),
                  endToEndId));
    }
  }

  private void rejectForFunds(
      Holder holder,
      PaymentId id,
      PaymentType type,
      Account debtor,
      Payee payee,
      Money amount,
      Instant createdAt,
      @Nullable EndToEndId endToEndId) {
    sink()
        .payment(
            new SeededPayment(
                new Payment(
                    id,
                    type,
                    PaymentStatus.REJECTED,
                    debtor.id(),
                    payee,
                    amount,
                    RejectionReason.INSUFFICIENT_FUNDS,
                    null),
                holder.customer(),
                createdAt,
                null,
                endToEndId));
  }

  private Holder openCustomer() {
    Instant openedAt = OPENING_FROM.plus(random.between(0, OPENING_SECONDS), ChronoUnit.SECONDS);
    CustomerId customer = new CustomerId(random.uuidV7(openedAt));
    sink().customer(new SeededCustomer(customer, faker.name().fullName(), openedAt));
    boolean overdraft = random.chance(OVERDRAFT_SHARE);
    Money overdraftLimit =
        overdraft ? Money.ofPence(random.between(1, 8) * 250_00) : Money.ofPence(0);
    Account current = open(customer, "Current Account", overdraftLimit, false, openedAt);
    Account savings =
        random.chance(SAVINGS_SHARE)
            ? open(customer, "Savings Account", Money.ofPence(0), false, openedAt)
            : null;
    Money pay = Money.ofPence(random.between(1_200, 4_500) * 100);
    Money rent = Money.ofPence(pay.pence() * random.between(30, 40) / 100);
    return new Holder(
        customer,
        current,
        savings,
        pay,
        (int) random.between(25, 28),
        rent,
        externalPayee(faker.name().fullName()),
        overdraft ? 1.08 : 1.0);
  }

  private Account openLoadTestAccount(int number) {
    String suffix = "%03d".formatted(number);
    Instant openedAt = OPENING_FROM.plus(OPENING_SECONDS, ChronoUnit.SECONDS);
    CustomerId customer = new CustomerId(random.uuidV7(openedAt));
    sink().customer(new SeededCustomer(customer, "Load Test Customer " + suffix, openedAt));
    return open(customer, "Load Test Account " + suffix, Money.ofPence(0), true, openedAt);
  }

  private Account open(
      CustomerId owner, String name, Money overdraftLimit, boolean loadTest, Instant openedAt) {
    Account account =
        new Account(
            new AccountId(random.uuidV7(openedAt)),
            name,
            AccountKind.CUSTOMER,
            Side.CREDIT,
            overdraftLimit,
            owner);
    sink()
        .account(
            new SeededAccount(
                account,
                SortCodes.FINTECH,
                String.valueOf(nextAccountNumber++),
                loadTest,
                openedAt));
    posting().open(account);
    return account;
  }

  /** An account at another UK bank, at a fictional sort code that isn't the fintech's. */
  private Payee.AtAnotherBank externalPayee(String name) {
    String sortCode = "00%04d".formatted(random.between(200, 9_999));
    return new Payee.AtAnotherBank(sortCode, random.digits(8), name);
  }

  private static Account internalAccount(AccountId id, String name) {
    return new Account(id, name, AccountKind.INTERNAL, Side.DEBIT, Money.ofPence(0), null);
  }

  // The schedule: everything happens in booking order, whenever it was planned.

  private void at(Instant moment, Runnable action) {
    schedule.add(new Scheduled(micros(moment), scheduled++, action));
  }

  private void runUntil(Instant end) {
    while (!schedule.isEmpty() && schedule.peek().at().isBefore(end)) {
      Scheduled next = schedule.poll();
      now = next.at();
      next.action().run();
    }
  }

  /** The moment the running action was scheduled for. */
  private Instant now() {
    if (now == null) {
      throw new IllegalStateException("No scheduled action is running");
    }
    return now;
  }

  /** A random moment, to the microsecond, between the hours of the day starting at the moment. */
  private Instant during(Instant dayStart, int fromHour, int toHour) {
    long micros = random.between(fromHour * 3_600_000_000L, toHour * 3_600_000_000L - 1);
    return dayStart.plus(micros, ChronoUnit.MICROS);
  }

  /** A random moment in working hours, from one to the given number of days after the moment. */
  private Instant duringNextDays(Instant moment, int fromDays, int toDays) {
    Instant day = moment.truncatedTo(ChronoUnit.DAYS);
    return during(day.plus(Duration.ofDays(random.between(fromDays, toDays))), 9, 17);
  }

  private static Instant startOf(LocalDate day) {
    return day.atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  /** Postgres keeps timestamps to the microsecond. */
  private static Instant micros(Instant moment) {
    return moment.truncatedTo(ChronoUnit.MICROS);
  }

  private SeedSink sink() {
    return Objects.requireNonNull(sink);
  }

  private SeedPosting posting() {
    return Objects.requireNonNull(posting);
  }

  /** Something to do at a moment; order breaks ties in the order it was planned. */
  private record Scheduled(Instant at, long order, Runnable action) {}

  /**
   * A Customer, their Customer Accounts (always a Current Account, sometimes Savings) and their
   * habits: monthly pay on payday, rent to a landlord, and how freely they spend.
   */
  private record Holder(
      CustomerId customer,
      Account current,
      @Nullable Account savings,
      Money pay,
      int payday,
      Money rent,
      Payee.AtAnotherBank landlord,
      double spendFactor) {}
}
