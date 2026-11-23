package com.paymentledger.ledger.views.internal;

import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** An Account's Statement from the Read Model, newest first. */
@RestController
class StatementController {

  private final Accounts accounts;
  private final JdbcReadModel readModel;

  StatementController(Accounts accounts, JdbcReadModel readModel) {
    this.accounts = accounts;
    this.readModel = readModel;
  }

  @GetMapping(path = "/{version}/accounts/{id}/statement", version = "1")
  StatementResponse statement(@AuthenticationPrincipal Principal principal, @PathVariable UUID id) {
    AccountId account =
        AccountViewController.AccountVisibility.require(accounts, principal, new AccountId(id))
            .id();
    List<LineResponse> lines =
        readModel.statementOf(account).stream().map(LineResponse::of).toList();
    return new StatementResponse(id, lines, null);
  }

  record StatementResponse(UUID accountId, List<LineResponse> lines, @Nullable String nextCursor) {}

  record LineResponse(
      UUID transactionId,
      Instant bookingTime,
      long entrySeq,
      String side,
      long amountPence,
      long balanceAfterPence,
      @Nullable UUID counterpartyAccountId) {

    static LineResponse of(StatementLine line) {
      return new LineResponse(
          line.transaction().value(),
          line.bookingTime(),
          line.entrySeq(),
          line.side().name(),
          line.amount().pence(),
          line.balanceAfter().pence(),
          line.counterparty() == null ? null : line.counterparty().value());
    }
  }
}
