package com.paymentledger.ledger.views.internal;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Principal;
import com.paymentledger.ledger.shared.Problem;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Accounts a Principal may see, with their Read Model Balances. The Balance trails the ledger
 * by the Projection Freshness; GET /v1/accounts/{id}/balance is the authoritative one.
 */
@RestController
class AccountViewController {

  private final Accounts accounts;
  private final JdbcReadModel readModel;

  AccountViewController(Accounts accounts, JdbcReadModel readModel) {
    this.accounts = accounts;
    this.readModel = readModel;
  }

  @GetMapping(path = "/{version}/accounts", version = "1")
  List<AccountResponse> accounts(@AuthenticationPrincipal Principal principal) {
    List<Account> visible = accounts.visibleTo(principal);
    Map<AccountId, Money> balances =
        readModel.balancesOf(visible.stream().map(Account::id).toList());
    return visible.stream()
        .map(account -> AccountResponse.of(account, balances.get(account.id())))
        .toList();
  }

  @GetMapping(path = "/{version}/accounts/{id}", version = "1")
  AccountResponse account(@AuthenticationPrincipal Principal principal, @PathVariable UUID id) {
    Account account = AccountVisibility.require(accounts, principal, new AccountId(id));
    return AccountResponse.of(
        account, readModel.balancesOf(List.of(account.id())).get(account.id()));
  }

  record AccountResponse(
      UUID id,
      String name,
      String kind,
      long balancePence,
      long overdraftLimitPence,
      long availableBalancePence) {

    /** An Account with no Entries in the Read Model has a Balance of zero. */
    static AccountResponse of(Account account, @Nullable Money readModelBalance) {
      Money balance = readModelBalance == null ? Money.ofPence(0) : readModelBalance;
      return new AccountResponse(
          account.id().value(),
          account.name(),
          account.kind().name(),
          balance.pence(),
          account.overdraftLimit().pence(),
          balance.plus(account.overdraftLimit()).pence());
    }
  }

  /** Refuses an Account the Principal may not see. */
  static final class AccountVisibility {

    private AccountVisibility() {}

    static Account require(Accounts accounts, Principal principal, AccountId id) {
      Account account =
          accounts.find(id).orElseThrow(() -> Problem.notFound("No Account " + id.value()));
      if (!account.isVisibleTo(principal)) {
        throw Problem.forbidden("Account " + id.value() + " is not yours to see");
      }
      return account;
    }
  }
}
