package com.paymentledger.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/** Property tests on the Transaction aggregate: it can never be built unbalanced. */
class TransactionAggregatePropertiesTest {

  private static final List<AccountId> ACCOUNTS =
      List.of(
          new AccountId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
          new AccountId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
          new AccountId(UUID.fromString("00000000-0000-0000-0000-000000000003")));

  /** Large enough to matter, small enough that five of them never overflow a long. */
  private static final long MAX_AMOUNT_PENCE = 1_000_000_000_000L;

  @Property
  void balanced_transactions_are_accepted(@ForAll("balancedEntries") List<Entry> entries) {
    assertThat(NewTransaction.funding(entries).entries()).containsExactlyElementsOf(entries);
  }

  @Property
  void unbalanced_transactions_are_refused(@ForAll("unbalancedEntries") List<Entry> entries) {
    assertThatThrownBy(() -> NewTransaction.funding(entries))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Property
  void every_accepted_transaction_has_debits_equal_to_credits(
      @ForAll("anyEntries") List<Entry> entries) {
    NewTransaction transaction;
    try {
      transaction = NewTransaction.funding(entries);
    } catch (IllegalArgumentException refused) {
      return;
    }
    assertThat(total(transaction.entries(), Side.DEBIT))
        .isEqualTo(total(transaction.entries(), Side.CREDIT));
  }

  @Property
  void entries_refuse_amounts_that_are_not_positive(
      @ForAll @LongRange(min = Long.MIN_VALUE, max = 0) long pence) {
    assertThatThrownBy(() -> new Entry(ACCOUNTS.getFirst(), Side.DEBIT, Money.ofPence(pence)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Provide
  Arbitrary<List<Entry>> anyEntries() {
    Arbitrary<List<Entry>> arbitrary =
        Combinators.combine(accounts(), Arbitraries.of(Side.class), amounts())
            .as((account, side, pence) -> new Entry(account, side, Money.ofPence(pence)))
            .list()
            .ofMinSize(0)
            .ofMaxSize(6);
    return Arbitraries.oneOf(balancedEntries(), unbalancedEntries(), arbitrary);
  }

  @Provide
  Arbitrary<List<Entry>> balancedEntries() {
    return amounts()
        .list()
        .ofMinSize(1)
        .ofMaxSize(5)
        .flatMap(debits ->
            splitInto(debits.stream().mapToLong(Long::longValue).sum())
                .flatMap(credits -> entries(debits, credits)));
  }

  /** A balanced Transaction with one Entry's amount nudged up, so the sides no longer agree. */
  @Provide
  Arbitrary<List<Entry>> unbalancedEntries() {
    return Combinators.combine(balancedEntries(), Arbitraries.integers(), amounts())
        .as((entries, index, extra) -> {
          List<Entry> nudged = new ArrayList<>(entries);
          int at = Math.floorMod(index, nudged.size());
          Entry entry = nudged.get(at);
          nudged.set(
              at,
              new Entry(
                  entry.account(), entry.side(), Money.ofPence(entry.amount().pence() + extra)));
          return List.copyOf(nudged);
        });
  }

  private static Arbitrary<Long> amounts() {
    return Arbitraries.longs().between(1, MAX_AMOUNT_PENCE);
  }

  private static Arbitrary<AccountId> accounts() {
    return Arbitraries.of(ACCOUNTS);
  }

  /** Splits a positive total into one to five positive parts. */
  private static Arbitrary<List<Long>> splitInto(long total) {
    return Arbitraries.integers()
        .between(1, (int) Math.min(5, total))
        .flatMap(parts ->
            parts == 1
                ? Arbitraries.just(List.of(total))
                : Arbitraries.longs()
                    .between(1, total - 1)
                    .set()
                    .ofSize(parts - 1)
                    .map(cuts -> partsBetween(cuts.stream().sorted().toList(), total)));
  }

  private static List<Long> partsBetween(List<Long> sortedCuts, long total) {
    List<Long> parts = new ArrayList<>();
    long previous = 0;
    for (long cut : sortedCuts) {
      parts.add(cut - previous);
      previous = cut;
    }
    parts.add(total - previous);
    return parts;
  }

  private static Arbitrary<List<Entry>> entries(List<Long> debits, List<Long> credits) {
    Arbitrary<List<Entry>> debitEntries = sideEntries(Side.DEBIT, debits);
    Arbitrary<List<Entry>> creditEntries = sideEntries(Side.CREDIT, credits);
    return Combinators.combine(debitEntries, creditEntries)
        .as((d, c) -> {
          List<Entry> all = new ArrayList<>(d);
          all.addAll(c);
          return all;
        })
        .flatMap(Arbitraries::shuffle);
  }

  private static Arbitrary<List<Entry>> sideEntries(Side side, List<Long> amounts) {
    return accounts()
        .list()
        .ofSize(amounts.size())
        .map(accounts -> {
          List<Entry> entries = new ArrayList<>();
          for (int i = 0; i < amounts.size(); i++) {
            entries.add(new Entry(accounts.get(i), side, Money.ofPence(amounts.get(i))));
          }
          return entries;
        });
  }

  private static BigInteger total(List<Entry> entries, Side side) {
    return entries.stream()
        .filter(entry -> entry.side() == side)
        .map(entry -> BigInteger.valueOf(entry.amount().pence()))
        .reduce(BigInteger.ZERO, BigInteger::add);
  }
}
