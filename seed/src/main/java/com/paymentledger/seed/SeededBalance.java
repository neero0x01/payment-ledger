package com.paymentledger.seed;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.util.Objects;

/**
 * An Account's balance row once every seeded Transaction is posted: its Balance on its Normal Side
 * and the last entry_seq handed out.
 */
public record SeededBalance(AccountId account, Money balance, long lastEntrySeq) {

  public SeededBalance {
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(balance, "balance");
  }
}
