package com.paymentledger.ledger.accounts;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An Account as other modules see it. A Customer Account has an owner; an Internal Account none.
 */
public record Account(
    AccountId id,
    AccountKind kind,
    Side normalSide,
    Money overdraftLimit,
    @Nullable CustomerId owner) {

  public Account {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(normalSide, "normalSide");
    Objects.requireNonNull(overdraftLimit, "overdraftLimit");
    if ((kind == AccountKind.CUSTOMER) != (owner != null)) {
      throw new IllegalArgumentException("A Customer Account, and only one, has an owner");
    }
  }

  /** Whether the Customer owns this Account. No Customer owns an Internal Account. */
  public boolean isOwnedBy(CustomerId customer) {
    return customer.equals(owner);
  }

  /**
   * Whether the funds check lets this Account pay out the amount given its Balance: only if the
   * amount is within the Available Balance (Balance plus Overdraft Limit). Internal Accounts have
   * no funds check.
   */
  public boolean canPayOut(Money amount, Money balance) {
    return !isCustomerAccount() || !amount.isGreaterThan(balance.plus(overdraftLimit));
  }

  /** Whether this is a Customer Account rather than an Internal Account. */
  public boolean isCustomerAccount() {
    return kind == AccountKind.CUSTOMER;
  }
}
