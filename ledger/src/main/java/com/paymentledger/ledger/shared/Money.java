package com.paymentledger.ledger.shared;

/** An amount of GBP, held as a whole number of pence. Negative only for Balances. */
public record Money(long pence) {

  public static Money ofPence(long pence) {
    return new Money(pence);
  }

  public boolean isPositive() {
    return pence > 0;
  }

  public boolean isGreaterThan(Money other) {
    return pence > other.pence;
  }

  public Money negate() {
    return new Money(Math.negateExact(pence));
  }
}
