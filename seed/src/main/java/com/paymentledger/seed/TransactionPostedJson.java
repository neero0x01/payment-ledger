package com.paymentledger.seed;

import com.paymentledger.ledger.outbox.EventId;
import com.paymentledger.ledger.posting.PostedEntry;
import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.TransactionId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * The TransactionPosted event of a seeded Transaction, in the shared envelope (ADR 0006), as the
 * outbox row's payload. Its JSON Schema is {@code docs/schemas/transaction-posted.v1.json}; the
 * ledger writes the same event for every Transaction it posts live.
 */
final class TransactionPostedJson {

  static final String TOPIC = "ledger.transaction-posted.v1";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private TransactionPostedJson() {}

  /** The event's JSON, announcing the Transaction under the eventId. */
  static String of(PostedTransaction transaction, EventId eventId) {
    String bookingTime = transaction.bookingTime().toString();
    Payload payload =
        new Payload(
            transaction.id().value(),
            transaction.kind().name(),
            bookingTime,
            transaction.payment().map(PaymentId::value).orElse(null),
            transaction.reverses().map(TransactionId::value).orElse(null),
            transaction.postedEntries().stream().map(EntryPayload::of).toList());
    return JSON.writeValueAsString(
        new Envelope(eventId.value(), "TransactionPosted", 1, bookingTime, payload));
  }

  record Envelope(
      UUID eventId, String type, int schemaVersion, String occurredAt, Payload payload) {}

  record Payload(
      UUID transactionId,
      String kind,
      String bookingTime,
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
