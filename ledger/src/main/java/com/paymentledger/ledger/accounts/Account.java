package com.paymentledger.ledger.accounts;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import java.util.Objects;

/** An Account as other modules see it. */
public record Account(AccountId id, AccountKind kind, Side normalSide, Money overdraftLimit) {

  public Account {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(normalSide, "normalSide");
    Objects.requireNonNull(overdraftLimit, "overdraftLimit");
  }

  /**
   * Whether the funds check lets this Account pay out the amount given its Balance: only if the
   * amount is within the Available Balance (Balance plus Overdraft Limit). Internal Accounts have
   * no funds check.
   */
  public boolean canPayOut(Money amount, Money balance) {
    return kind == AccountKind.INTERNAL || !amount.isGreaterThan(balance.plus(overdraftLimit));
  }
}
