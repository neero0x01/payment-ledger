package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.Money;
import java.util.Objects;

/**
 * An Entry as recorded in the ledger: its place in its Account's sequence of Entries and the
 * Account's Balance just after it.
 */
public record PostedEntry(Entry entry, long entrySeq, Money balanceAfter) {

  public PostedEntry {
    Objects.requireNonNull(entry, "entry");
    Objects.requireNonNull(balanceAfter, "balanceAfter");
    if (entrySeq < 1) {
      throw new IllegalArgumentException("An entry_seq starts at 1, was " + entrySeq);
    }
  }
}
