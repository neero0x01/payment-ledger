package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import java.util.Objects;

/** One line of a Transaction: a positive amount posted to one Account on one side. */
public record Entry(AccountId account, Side side, Money amount) {

  public Entry {
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(amount, "amount");
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("An Entry's amount must be positive, was " + amount);
    }
  }

  /** The same amount on the same Account, on the other side. */
  public Entry mirrored() {
    return new Entry(account, side == Side.DEBIT ? Side.CREDIT : Side.DEBIT, amount);
  }

  /** The same amount on the other side, posted to another Account. */
  public Entry mirroredOnto(AccountId other) {
    return new Entry(other, mirrored().side(), amount);
  }

  /** How this Entry moves the Balance of an Account with the given Normal Side. */
  public Money balanceChange(Side normalSide) {
    return side == normalSide ? amount : amount.negate();
  }
}
