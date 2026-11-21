package com.paymentledger.ledger.accounts;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import java.util.Objects;

/** An Account as other modules see it. */
public record Account(AccountId id, Side normalSide, Money overdraftLimit) {

  public Account {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(normalSide, "normalSide");
    Objects.requireNonNull(overdraftLimit, "overdraftLimit");
  }

  /** Balance plus Overdraft Limit: the most this Account can pay out right now. */
  public Money availableBalance(Money balance) {
    return balance.plus(overdraftLimit);
  }
}
