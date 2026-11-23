package com.paymentledger.ledger.payments.internal;

import com.paymentledger.ledger.payments.Payee;
import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.RejectionReason;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.IdempotencyKey;
import com.paymentledger.ledger.shared.IdempotentOutcome;
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

  private final PaymentService payments;
  private final Posting posting;

  PaymentController(PaymentService payments, Posting posting) {
    this.payments = payments;
    this.posting = posting;
  }

  @PostMapping(path = "/{version}/payments", version = "1")
  ResponseEntity<PaymentResponse> makePayment(
      @AuthenticationPrincipal Principal principal,
      @RequestHeader(name = IdempotencyKey.HEADER, required = false)
          @Nullable String idempotencyKey,
      @RequestBody PaymentRequest request,
      UriComponentsBuilder uri) {
    CustomerId customer =
        principal.customerId().orElseThrow(() -> Problem.forbidden("Only Customers make Payments"));
    IdempotencyKey key = IdempotencyKey.fromHeader(idempotencyKey);
    AccountId debtor = new AccountId(request.debtorAccountId());
    Money amount = Money.ofPence(request.amountPence());
    IdempotentOutcome<Payment> made =
        switch (request.payee().toPayee()) {
          case Payee.AtTheFintech payee ->
              payments.makeInternalTransfer(customer, key, debtor, payee.account(), amount);
          case Payee.AtAnotherBank payee ->
              payments.makeFasterPayment(customer, key, debtor, payee, amount);
        };
    Payment payment = made.outcome();
    if (made.replayed()) {
      return ResponseEntity.ok()
          .header(IdempotentOutcome.REPLAYED_HEADER, "true")
          .body(toResponse(payment));
    }
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

  /**
   * A Payee as the API sends it: {@code {accountId}} for an Account at the fintech, or {@code
   * {sortCode, accountNumber, name}} for an account at another bank.
   */
  record PayeeBody(
      @Nullable UUID accountId,
      @Nullable String sortCode,
      @Nullable String accountNumber,
      @Nullable String name) {

    static PayeeBody of(Payee payee) {
      return switch (payee) {
        case Payee.AtTheFintech p -> new PayeeBody(p.account().value(), null, null, null);
        case Payee.AtAnotherBank p ->
            new PayeeBody(null, p.sortCode(), p.accountNumber(), p.name());
      };
    }

    Payee toPayee() {
      if (accountId == null) {
        return new Payee.AtAnotherBank(sortCode, accountNumber, name);
      }
      if (sortCode != null || accountNumber != null || name != null) {
        throw Problem.validationFailed(
            "The Payee is either an accountId or a sort code, account number and name");
      }
      return new Payee.AtTheFintech(new AccountId(accountId));
    }
  }

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
          PayeeBody.of(payment.payee()),
          payment.amount().pence(),
          payment.rejection().map(RejectionReason::name).orElse(null),
          transactions.stream().map(TransactionId::value).toList());
    }
  }
}
