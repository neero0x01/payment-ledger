package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.posting.CorrectionDetails;
import com.paymentledger.ledger.posting.CorrectionReason;
import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.posting.internal.TransactionController.TransactionResponse;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.IdempotencyKey;
import com.paymentledger.ledger.shared.IdempotentOutcome;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.OperatorId;
import com.paymentledger.ledger.shared.Principal;
import com.paymentledger.ledger.shared.Problem;
import com.paymentledger.ledger.shared.Side;
import com.paymentledger.ledger.shared.TransactionId;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
      @AuthenticationPrincipal Principal principal,
      @RequestHeader(name = IdempotencyKey.HEADER, required = false)
          @Nullable String idempotencyKey,
      @RequestBody CorrectionRequest request,
      UriComponentsBuilder uri) {
    OperatorId operator =
        principal
            .operatorId()
            .orElseThrow(() -> Problem.forbidden("Only Operators post Corrections"));
    IdempotencyKey key = IdempotencyKey.fromHeader(idempotencyKey);
    CorrectionKind kind = request.kind();
    if (kind == null) {
      throw Problem.validationFailed("A Correction needs a kind");
    }
    CorrectionDetails details = details(request);
    IdempotentOutcome<PostedTransaction> posted =
        switch (kind) {
          case REVERSING -> reverse(operator, key, request, details);
          case ADJUSTMENT -> adjust(operator, key, request, details);
        };
    PostedTransaction correction = posted.outcome();
    if (posted.replayed()) {
      return ResponseEntity.ok()
          .header(IdempotentOutcome.REPLAYED_HEADER, "true")
          .body(TransactionResponse.of(correction));
    }
    return ResponseEntity.created(
            uri.path("/v1/transactions/{id}").buildAndExpand(correction.id().value()).toUri())
        .body(TransactionResponse.of(correction));
  }

  private IdempotentOutcome<PostedTransaction> reverse(
      OperatorId operator,
      IdempotencyKey key,
      CorrectionRequest request,
      CorrectionDetails details) {
    UUID transaction = request.transactionId();
    if (transaction == null) {
      throw Problem.validationFailed("A Reversing Correction needs a transactionId");
    }
    return corrections.reverse(operator, key, new TransactionId(transaction), details);
  }

  private IdempotentOutcome<PostedTransaction> adjust(
      OperatorId operator,
      IdempotencyKey key,
      CorrectionRequest request,
      CorrectionDetails details) {
    UUID account = request.accountId();
    Long amountPence = request.amountPence();
    Side direction = request.direction();
    if (account == null || amountPence == null || direction == null) {
      throw Problem.validationFailed(
          "An Adjustment needs an accountId, an amountPence and a direction");
    }
    return corrections.adjust(
        operator,
        key,
        new AccountId(account),
        direction,
        Problem.requirePositive(Money.ofPence(amountPence)),
        details,
        Optional.ofNullable(request.transactionId()).map(TransactionId::new));
  }

  /** Every Correction carries a Correction Reason and a note. */
  private static CorrectionDetails details(CorrectionRequest request) {
    CorrectionReason reason = request.reason();
    if (reason == null) {
      throw Problem.validationFailed("A Correction needs a Correction Reason");
    }
    String note = request.note();
    if (note == null || note.isBlank()) {
      throw Problem.validationFailed("A Correction needs a note");
    }
    return new CorrectionDetails(reason, note);
  }

  enum CorrectionKind {
    REVERSING,
    ADJUSTMENT
  }

  /**
   * For an Adjustment, the direction is the side posted to the Customer Account, and the optional
   * transactionId names the Transaction it corrects.
   */
  record CorrectionRequest(
      @Nullable CorrectionKind kind,
      @Nullable UUID transactionId,
      @Nullable CorrectionReason reason,
      @Nullable String note,
      @Nullable UUID accountId,
      @Nullable Long amountPence,
      @Nullable Side direction) {}
}
