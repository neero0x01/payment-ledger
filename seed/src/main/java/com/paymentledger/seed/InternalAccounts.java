package com.paymentledger.seed;

import com.paymentledger.ledger.shared.AccountId;
import java.util.Objects;

/** The ledger's Internal Accounts that seeded Transactions post to, by their ids in the ledger. */
public record InternalAccounts(AccountId funding, AccountId fpsClearing, AccountId corrections) {

  public InternalAccounts {
    Objects.requireNonNull(funding, "funding");
    Objects.requireNonNull(fpsClearing, "fpsClearing");
    Objects.requireNonNull(corrections, "corrections");
  }
}
