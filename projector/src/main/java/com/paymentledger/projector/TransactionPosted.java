package com.paymentledger.projector;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A TransactionPosted event from {@code ledger.transaction-posted.v1}: the envelope of ADR 0006
 * around its payload, as {@code docs/schemas/transaction-posted.v1.json} describes it. Only the
 * fields the projector reads.
 */
record TransactionPosted(UUID eventId, Payload payload) {

  record Payload(UUID transactionId, Instant bookingTime, List<PostedEntry> entries) {}

  record PostedEntry(
      UUID accountId, String side, long amountPence, long entrySeq, long balanceAfterPence) {}

  /** The Account on the other side of a two-Entry Transaction, or null when it has more Entries. */
  @Nullable UUID counterpartyOf(PostedEntry entry) {
    List<PostedEntry> entries = payload.entries();
    if (entries.size() != 2) {
      return null;
    }
    return entries.get(0).equals(entry) ? entries.get(1).accountId() : entries.get(0).accountId();
  }
}
