package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
class PaymentController {

  private final PaymentService payments;

  PaymentController(PaymentService payments) {
    this.payments = payments;
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
        .body(PaymentResponse.of(payment));
  }

  record PaymentRequest(UUID debtorAccountId, PayeeBody payee, long amountPence) {}

  record PayeeBody(UUID accountId) {}

  record PaymentResponse(
      UUID id,
      String type,
      String status,
      UUID debtorAccountId,
      PayeeBody payee,
      long amountPence) {

    static PaymentResponse of(Payment payment) {
      return new PaymentResponse(
          payment.id().value(),
          payment.type().name(),
          payment.status().name(),
          payment.debtorAccount().value(),
          new PayeeBody(payment.payeeAccount().value()),
          payment.amount().pence());
    }
  }
}
