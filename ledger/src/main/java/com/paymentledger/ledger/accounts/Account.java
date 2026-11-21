package com.paymentledger.ledger.accounts;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Side;
import java.util.Objects;

/** An Account as other modules see it. */
public record Account(AccountId id, Side normalSide) {

  public Account {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(normalSide, "normalSide");
  }
}
