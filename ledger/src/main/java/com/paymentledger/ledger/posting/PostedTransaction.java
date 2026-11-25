package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.TransactionId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A Transaction as recorded in the ledger, posted live or loaded by the seed. It never changes once
 * posted.
 */
public record PostedTransaction(
    TransactionId id,
    TransactionKind kind,
    Instant bookingTime,
    @Nullable PaymentId originatingPayment,
    @Nullable TransactionId reversedTransaction,
    @Nullable CorrectionDetails correctionDetails,
    @Nullable TransactionId adjustedTransaction,
    TransactionSource source,
    List<PostedEntry> postedEntries) {

  public PostedTransaction {
    Objects.requireNonNull(source, "source");
    postedEntries = List.copyOf(postedEntries);
  }

  /** The Transaction's Entries, without their place in each Account's sequence. */
  public List<Entry> entries() {
    return postedEntries.stream().map(PostedEntry::entry).toList();
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

  /**
   * The Transaction this Correction corrects: the one a Reversing Correction reverses, or the one
   * an Adjustment names. Empty for a goodwill Adjustment and for any other kind of Transaction.
   */
  public Optional<TransactionId> corrects() {
    if (kind != TransactionKind.CORRECTION) {
      return Optional.empty();
    }
    return reverses().or(this::adjusts);
  }

  /** The Correction Reason and note, if this Transaction is a Correction. */
  public Optional<CorrectionDetails> correction() {
    return Optional.ofNullable(correctionDetails);
  }
}
