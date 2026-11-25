package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.PostedEntry;
import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.TransactionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class TransactionController {

  private final Posting posting;

  TransactionController(Posting posting) {
    this.posting = posting;
  }

  @GetMapping(path = "/{version}/transactions/{id}", version = "1")
  TransactionResponse transaction(@PathVariable UUID id) {
    return posting
        .find(new TransactionId(id))
        .map(transaction ->
            TransactionResponse.of(
                transaction,
                posting.reversalOf(transaction.id()),
                posting.correctionsOf(transaction.id())))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  record TransactionResponse(
      UUID id,
      String kind,
      Instant bookingTime,
      @Nullable UUID paymentId,
      @Nullable UUID reversesTransactionId,
      @Nullable String correctionReason,
      @Nullable String correctionNote,
      String source,
      List<EntryResponse> entries,
      @Nullable UUID reversedByTransactionId,
      @Nullable UUID correctsTransactionId,
      List<UUID> correctedByTransactionIds) {

    /** A Transaction just posted, which nothing has reversed or corrected yet. */
    static TransactionResponse of(PostedTransaction transaction) {
      return of(transaction, Optional.empty(), List.of());
    }

    static TransactionResponse of(
        PostedTransaction transaction,
        Optional<TransactionId> reversedBy,
        List<TransactionId> correctedBy) {
      CorrectionFields correction = CorrectionFields.of(transaction.correction());
      return new TransactionResponse(
          transaction.id().value(),
          transaction.kind().name(),
          transaction.bookingTime(),
          transaction.payment().map(PaymentId::value).orElse(null),
          transaction.reverses().map(TransactionId::value).orElse(null),
          correction.reason(),
          correction.note(),
          transaction.source().name(),
          transaction.postedEntries().stream().map(EntryResponse::of).toList(),
          reversedBy.map(TransactionId::value).orElse(null),
          transaction.corrects().map(TransactionId::value).orElse(null),
          correctedBy.stream().map(TransactionId::value).toList());
    }
  }

  record EntryResponse(
      UUID accountId, String side, long amountPence, long entrySeq, long balanceAfterPence) {

    static EntryResponse of(PostedEntry posted) {
      Entry entry = posted.entry();
      return new EntryResponse(
          entry.account().value(),
          entry.side().name(),
          entry.amount().pence(),
          posted.entrySeq(),
          posted.balanceAfter().pence());
    }
  }
}
