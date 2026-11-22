package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.Principal;
import com.paymentledger.ledger.shared.Problem;
import com.paymentledger.ledger.shared.TransactionId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
class PaymentController {

  static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private final PaymentService payments;
  private final Posting posting;

  PaymentController(PaymentService payments, Posting posting) {
    this.payments = payments;
    this.posting = posting;
  }

  @PostMapping(path = "/{version}/payments", version = "1")
  ResponseEntity<PaymentResponse> makePayment(
      @AuthenticationPrincipal Principal principal,
      @RequestHeader(name = IDEMPOTENCY_KEY, required = false) @Nullable String idempotencyKey,
      @RequestBody PaymentRequest request,
      UriComponentsBuilder uri) {
    CustomerId customer =
        principal.customerId().orElseThrow(() -> Problem.forbidden("Only Customers make Payments"));
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw Problem.idempotencyKeyMissing("A Payment needs an Idempotency-Key header");
    }
    Payment payment =
        payments.makeInternalTransfer(
            customer,
            new AccountId(request.debtorAccountId()),
            new AccountId(request.payee().accountId()),
            Money.ofPence(request.amountPence()));
    return ResponseEntity.created(
            uri.path("/v1/payments/{id}").buildAndExpand(payment.id().value()).toUri())
        .body(toResponse(payment));
  }

  @GetMapping(path = "/{version}/payments/{id}", version = "1")
  PaymentResponse payment(@AuthenticationPrincipal Principal principal, @PathVariable UUID id) {
    return payments
        .find(new PaymentId(id), principal)
        .map(this::toResponse)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private PaymentResponse toResponse(Payment payment) {
    return PaymentResponse.of(payment, posting.transactionsOf(payment.id()));
  }

  record PaymentRequest(UUID debtorAccountId, PayeeBody payee, long amountPence) {}

  record PayeeBody(UUID accountId) {}

  record PaymentResponse(
      UUID id,
      String type,
      String status,
      UUID debtorAccountId,
      PayeeBody payee,
      long amountPence,
      @Nullable String rejectionReason,
      List<UUID> transactionIds) {

    static PaymentResponse of(Payment payment, List<TransactionId> transactions) {
      return new PaymentResponse(
          payment.id().value(),
          payment.type().name(),
          payment.status().name(),
          payment.debtorAccount().value(),
          new PayeeBody(payment.payeeAccount().value()),
          payment.amount().pence(),
          payment.rejection().map(RejectionReason::name).orElse(null),
          transactions.stream().map(TransactionId::value).toList());
    }
  }
}
