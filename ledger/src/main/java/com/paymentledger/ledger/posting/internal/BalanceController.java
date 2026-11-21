package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.AccountId;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class BalanceController {

  private final Posting posting;

  BalanceController(Posting posting) {
    this.posting = posting;
  }

  @GetMapping(path = "/{version}/accounts/{id}/balance", version = "1")
  BalanceResponse balance(@PathVariable UUID id) {
    return posting
        .balanceOf(new AccountId(id))
        .map(balance -> new BalanceResponse(id, balance.pence()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  record BalanceResponse(UUID accountId, long balancePence) {}
}
