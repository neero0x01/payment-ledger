package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.outbox.OutboxEvent;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.PostedEntry;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.TransactionId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The TransactionPosted event on {@code ledger.transaction-posted.v1}, keyed by Transaction id. Its
 * JSON Schema is {@code docs/schemas/transaction-posted.v1.json}; a breaking change means a new
 * topic (ADR 0006).
 */
final class TransactionPostedEvent {

  static final String TOPIC = "ledger.transaction-posted.v1";
  static final String TYPE = "TransactionPosted";
  static final int SCHEMA_VERSION = 1;

  private TransactionPostedEvent() {}

  static OutboxEvent of(
      TransactionId id,
      NewTransaction transaction,
      Instant bookingTime,
      List<PostedEntry> postedEntries) {
    Payload payload =
        new Payload(
            id.value(),
            transaction.kind().name(),
            bookingTime,
            transaction.payment().map(PaymentId::value).orElse(null),
            transaction.reverses().map(TransactionId::value).orElse(null),
            postedEntries.stream().map(EntryPayload::of).toList());
    return new OutboxEvent(
        TOPIC, id.value().toString(), TYPE, SCHEMA_VERSION, bookingTime, payload);
  }

  record Payload(
      UUID transactionId,
      String kind,
      Instant bookingTime,
      @Nullable UUID paymentId,
      @Nullable UUID reversesTransactionId,
      List<EntryPayload> entries) {}

  record EntryPayload(
      UUID accountId, String side, long amountPence, long entrySeq, long balanceAfterPence) {

    static EntryPayload of(PostedEntry posted) {
      return new EntryPayload(
          posted.entry().account().value(),
          posted.entry().side().name(),
          posted.entry().amount().pence(),
          posted.entrySeq(),
          posted.balanceAfter().pence());
    }
  }
}
