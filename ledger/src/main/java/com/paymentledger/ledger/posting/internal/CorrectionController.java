package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.posting.internal.TransactionController.TransactionResponse;
import com.paymentledger.ledger.shared.TransactionId;
import java.util.UUID;
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
    PostedTransaction correction =
        switch (request.kind()) {
          case REVERSING -> corrections.reverse(new TransactionId(request.transactionId()));
        };
    return ResponseEntity.created(
            uri.path("/v1/transactions/{id}").buildAndExpand(correction.id().value()).toUri())
        .body(TransactionResponse.of(correction));
  }

  enum CorrectionKind {
    REVERSING
  }

  record CorrectionRequest(CorrectionKind kind, UUID transactionId) {}
}
