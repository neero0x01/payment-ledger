package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.posting.CorrectionDetails;
import com.paymentledger.ledger.posting.CorrectionReason;
import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.posting.internal.TransactionController.TransactionResponse;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.IdempotencyKey;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.OperatorId;
import com.paymentledger.ledger.shared.Principal;
import com.paymentledger.ledger.shared.Problem;
import com.paymentledger.ledger.shared.Side;
import com.paymentledger.ledger.shared.TransactionId;
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

  static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

  private final CorrectionService corrections;

  CorrectionController(CorrectionService corrections) {
    this.corrections = corrections;
  }

  @PostMapping(path = "/{version}/corrections", version = "1")
  ResponseEntity<TransactionResponse> postCorrection(
      @AuthenticationPrincipal Principal principal,
      @RequestHeader(name = IDEMPOTENCY_KEY, required = false) @Nullable String idempotencyKey,
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
    CorrectionService.Posted posted =
        switch (kind) {
          case REVERSING -> reverse(operator, key, request, details);
          case ADJUSTMENT -> adjust(operator, key, request, details);
        };
    PostedTransaction correction = posted.correction();
    if (posted.replayed()) {
      return ResponseEntity.ok()
          .header(IDEMPOTENT_REPLAYED, "true")
          .body(TransactionResponse.of(correction));
    }
    return ResponseEntity.created(
            uri.path("/v1/transactions/{id}").buildAndExpand(correction.id().value()).toUri())
        .body(TransactionResponse.of(correction));
  }

  private CorrectionService.Posted reverse(
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

  private CorrectionService.Posted adjust(
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
        details);
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
