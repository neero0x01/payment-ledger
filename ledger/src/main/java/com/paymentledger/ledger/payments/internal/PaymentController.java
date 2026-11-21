package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.TransactionId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
class PaymentController {

  private final PaymentService payments;
  private final Posting posting;

  PaymentController(PaymentService payments, Posting posting) {
    this.payments = payments;
    this.posting = posting;
  }

  @PostMapping(path = "/{version}/payments", version = "1")
  ResponseEntity<PaymentResponse> makePayment(
      @RequestBody PaymentRequest request, UriComponentsBuilder uri) {
    Payment payment =
        payments.makeInternalTransfer(
            new AccountId(request.debtorAccountId()),
            new AccountId(request.payee().accountId()),
            Money.ofPence(request.amountPence()));
    return ResponseEntity.created(
            uri.path("/v1/payments/{id}").buildAndExpand(payment.id().value()).toUri())
        .body(toResponse(payment));
  }

  @GetMapping(path = "/{version}/payments/{id}", version = "1")
  PaymentResponse payment(@PathVariable UUID id) {
    return payments
        .find(new PaymentId(id))
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
      List<UUID> transactionIds) {

    static PaymentResponse of(Payment payment, List<TransactionId> transactions) {
      return new PaymentResponse(
          payment.id().value(),
          payment.type().name(),
          payment.status().name(),
          payment.debtorAccount().value(),
          new PayeeBody(payment.payeeAccount().value()),
          payment.amount().pence(),
          transactions.stream().map(TransactionId::value).toList());
    }
  }
}
