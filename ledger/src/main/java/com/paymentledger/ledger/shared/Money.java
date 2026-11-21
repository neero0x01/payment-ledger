package com.paymentledger.ledger.shared;

import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in a currency's minor unit; in v1 always GBP pence. Negative only for
 * Balances. Amounts in different currencies never mix, and sums that would overflow are refused.
 */
public record Money(long pence, Currency currency) {

  private static final Currency GBP = Currency.getInstance("GBP");

  public Money {
    Objects.requireNonNull(currency, "currency");
  }

  public static Money ofPence(long pence) {
    return new Money(pence, GBP);
  }

  public static Money of(long minorUnits, Currency currency) {
    return new Money(minorUnits, currency);
  }

  public boolean isPositive() {
    return pence > 0;
  }

  public boolean isGreaterThan(Money other) {
    requireSameCurrency(other);
    return pence > other.pence;
  }

  /** The sum of two amounts in the same currency; refuses a sum that would overflow. */
  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(Math.addExact(pence, other.pence), currency);
  }

  public Money negate() {
    return new Money(Math.negateExact(pence), currency);
  }

  private void requireSameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException(
          "Cannot combine " + other.currency + " with " + currency + ": currencies never mix");
    }
  }
}
