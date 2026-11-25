package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.Side;
import com.paymentledger.ledger.shared.TransactionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The Transaction aggregate: a Transaction to be posted. It cannot be built unless it has at least
 * two Entries and its Debits total equals its Credits total.
 */
public record NewTransaction(
    TransactionKind kind,
    @Nullable PaymentId originatingPayment,
    @Nullable TransactionId reversedTransaction,
    @Nullable CorrectionDetails correctionDetails,
    @Nullable TransactionId adjustedTransaction,
    List<Entry> entries) {

  public NewTransaction {
    Objects.requireNonNull(kind, "kind");
    entries = List.copyOf(entries);
    if (adjustedTransaction != null
        && (kind != TransactionKind.CORRECTION || reversedTransaction != null)) {
      throw new IllegalArgumentException("Only an Adjustment names the Transaction it adjusts");
    }
    if (entries.size() < 2) {
      throw new IllegalArgumentException("A Transaction needs at least two Entries");
    }
    Money debits = total(entries, Side.DEBIT);
    Money credits = total(entries, Side.CREDIT);
    if (!debits.equals(credits)) {
      throw new IllegalArgumentException(
          "A Transaction's Debits must equal its Credits, were " + debits + " and " + credits);
    }
  }

  public static NewTransaction funding(List<Entry> entries) {
    return new NewTransaction(TransactionKind.FUNDING, null, null, null, null, entries);
  }

  public static NewTransaction forPayment(PaymentId payment, List<Entry> entries) {
    return new NewTransaction(
        TransactionKind.PAYMENT, Objects.requireNonNull(payment), null, null, null, entries);
  }

  /**
   * The Reversal of a Payment's Transaction after a Scheme Rejection: its Entries mirrored with the
   * sides swapped, linked to the original and to the same Payment.
   */
  public static NewTransaction reversal(PostedTransaction original) {
    PaymentId payment =
        original
            .payment()
            .orElseThrow(
                () -> new IllegalArgumentException("Only a Payment's Transaction has a Reversal"));
    return new NewTransaction(
        TransactionKind.REVERSAL,
        payment,
        original.id(),
        null,
        null,
        original.entries().stream().map(Entry::mirrored).toList());
  }

  /**
   * A Reversing Correction of the original: its Entries mirrored with the sides swapped, linked to
   * the original, which is never changed.
   */
  public static NewTransaction reversingCorrection(
      PostedTransaction original, CorrectionDetails correction) {
    return new NewTransaction(
        TransactionKind.CORRECTION,
        null,
        original.id(),
        Objects.requireNonNull(correction),
        null,
        original.entries().stream().map(Entry::mirrored).toList());
  }

  /**
   * An Adjustment: the amount posted to the Customer Account on the given side, and to the
   * Corrections Account on the other. It names the Transaction it corrects, or none for goodwill.
   */
  public static NewTransaction adjustment(
      AccountId customerAccount,
      Side customerSide,
      Money amount,
      AccountId correctionsAccount,
      CorrectionDetails correction,
      Optional<TransactionId> corrects) {
    Entry customerEntry = new Entry(customerAccount, customerSide, amount);
    return new NewTransaction(
        TransactionKind.CORRECTION,
        null,
        null,
        Objects.requireNonNull(correction),
        corrects.orElse(null),
        List.of(customerEntry.mirroredOnto(correctionsAccount), customerEntry));
  }

  public Optional<PaymentId> payment() {
    return Optional.ofNullable(originatingPayment);
  }

  /** The Transaction this one reverses, if it is a Reversing Correction. */
  public Optional<TransactionId> reverses() {
    return Optional.ofNullable(reversedTransaction);
  }

  /** The Transaction this one adjusts, if it is an Adjustment that names one. */
  public Optional<TransactionId> adjusts() {
    return Optional.ofNullable(adjustedTransaction);
  }

  /** The Correction Reason and note, if this Transaction is a Correction. */
  public Optional<CorrectionDetails> correction() {
    return Optional.ofNullable(correctionDetails);
  }

  private static Money total(List<Entry> entries, Side side) {
    return entries.stream()
        .filter(entry -> entry.side() == side)
        .map(Entry::amount)
        .reduce(Money.ofPence(0), Money::plus);
  }
}
