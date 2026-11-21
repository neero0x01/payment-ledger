package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.posting.CorrectionDetails;
import com.paymentledger.ledger.posting.CorrectionReason;
import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.posting.internal.TransactionController.TransactionResponse;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import com.paymentledger.ledger.shared.TransactionId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
class CorrectionController {

  private final CorrectionService corrections;

  CorrectionController(CorrectionService corrections) {
    this.corrections = corrections;
  }

  @PostMapping(path = "/{version}/corrections", version = "1")
  ResponseEntity<TransactionResponse> postCorrection(
      @RequestBody CorrectionRequest request, UriComponentsBuilder uri) {
    CorrectionKind kind = request.kind();
    if (kind == null) {
      throw CorrectionProblems.validationFailed("A Correction needs a kind");
    }
    CorrectionDetails details = details(request);
    PostedTransaction correction =
        switch (kind) {
          case REVERSING -> reverse(request, details);
          case ADJUSTMENT -> adjust(request, details);
        };
    return ResponseEntity.created(
            uri.path("/v1/transactions/{id}").buildAndExpand(correction.id().value()).toUri())
        .body(TransactionResponse.of(correction));
  }

  private PostedTransaction reverse(CorrectionRequest request, CorrectionDetails details) {
    UUID transaction = request.transactionId();
    if (transaction == null) {
      throw CorrectionProblems.validationFailed("A Reversing Correction needs a transactionId");
    }
    return corrections.reverse(new TransactionId(transaction), details);
  }

  private PostedTransaction adjust(CorrectionRequest request, CorrectionDetails details) {
    UUID account = request.accountId();
    Long amountPence = request.amountPence();
    Side direction = request.direction();
    if (account == null || amountPence == null || direction == null) {
      throw CorrectionProblems.validationFailed(
          "An Adjustment needs an accountId, an amountPence and a direction");
    }
    if (amountPence <= 0) {
      throw CorrectionProblems.validationFailed("The amount must be more than zero");
    }
    return corrections.adjust(
        new AccountId(account), direction, Money.ofPence(amountPence), details);
  }

  /** Every Correction carries a Correction Reason and a note. */
  private static CorrectionDetails details(CorrectionRequest request) {
    CorrectionReason reason = request.reason();
    if (reason == null) {
      throw CorrectionProblems.validationFailed("A Correction needs a Correction Reason");
    }
    String note = request.note();
    if (note == null || note.isBlank()) {
      throw CorrectionProblems.validationFailed("A Correction needs a note");
    }
    return new CorrectionDetails(reason, note);
  }

  enum CorrectionKind {
    REVERSING,
    ADJUSTMENT
  }

  /** For an Adjustment, the direction is the side posted to the Customer Account. */
  record CorrectionRequest(
      @Nullable CorrectionKind kind,
      @Nullable UUID transactionId,
      @Nullable CorrectionReason reason,
      @Nullable String note,
      @Nullable UUID accountId,
      @Nullable Long amountPence,
      @Nullable Side direction) {}
}
