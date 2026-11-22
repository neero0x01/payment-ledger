package com.paymentledger.ledger.posting.internal;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Principal;
import com.paymentledger.ledger.shared.Problem;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class BalanceController {

  private final Posting posting;
  private final Accounts accounts;

  BalanceController(Posting posting, Accounts accounts) {
    this.posting = posting;
    this.accounts = accounts;
  }

  /**
   * The Balance of an Account its owner or an Operator may see, or its As-At Balance when asAt is
   * given.
   */
  @GetMapping(path = "/{version}/accounts/{id}/balance", version = "1")
  BalanceResponse balance(
      @AuthenticationPrincipal Principal principal,
      @PathVariable UUID id,
      @RequestParam(required = false) @Nullable Instant asAt) {
    AccountId accountId = new AccountId(id);
    Account account =
        accounts
            .find(accountId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!account.isVisibleTo(principal)) {
      throw Problem.forbidden("Account " + id + " is not yours to see");
    }
    Money balance =
        asAt == null
            ? posting.balanceOf(accountId).orElseThrow()
            : posting.balanceOf(accountId, asAt).orElseThrow();
    return new BalanceResponse(id, balance.pence());
  }

  record BalanceResponse(UUID accountId, long balancePence) {}
}
