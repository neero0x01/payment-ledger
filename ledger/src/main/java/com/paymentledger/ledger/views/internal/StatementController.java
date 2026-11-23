package com.paymentledger.ledger.views.internal;

import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Principal;
import com.paymentledger.ledger.shared.Problem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** An Account's Statement from the Read Model, a page at a time. */
@RestController
class StatementController {

  static final int PAGE_SIZE = 50;

  private final Accounts accounts;
  private final JdbcReadModel readModel;

  StatementController(Accounts accounts, JdbcReadModel readModel) {
    this.accounts = accounts;
    this.readModel = readModel;
  }

  /**
   * One page of the Account's Statement, newest first: Statement Lines booked from (inclusive) to
   * (exclusive), keyset-paged on Booking Time and entry_seq. nextCursor fetches the next page, and
   * is null on the last.
   */
  @GetMapping(path = "/{version}/accounts/{id}/statement", version = "1")
  StatementResponse statement(
      @AuthenticationPrincipal Principal principal,
      @PathVariable UUID id,
      @RequestParam(required = false) @Nullable Instant from,
      @RequestParam(required = false) @Nullable Instant to,
      @RequestParam(required = false) @Nullable String cursor) {
    if (from != null && to != null && from.isAfter(to)) {
      throw Problem.validationFailed("from must not be after to");
    }
    StatementCursor after = cursor == null ? null : StatementCursor.decode(cursor);
    AccountId account =
        AccountViewController.AccountVisibility.require(accounts, principal, new AccountId(id))
            .id();
    List<StatementLine> lines = readModel.statementPage(account, from, to, after, PAGE_SIZE + 1);
    boolean more = lines.size() > PAGE_SIZE;
    List<StatementLine> page = more ? lines.subList(0, PAGE_SIZE) : lines;
    return new StatementResponse(
        id,
        page.stream().map(LineResponse::of).toList(),
        more ? StatementCursor.after(page.getLast()).encode() : null);
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
