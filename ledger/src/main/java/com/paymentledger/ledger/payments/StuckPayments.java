package com.paymentledger.ledger.payments;

import java.util.List;

/** The Stuck Payments, which wait for an Operator: they are never reversed automatically. */
public interface StuckPayments {

  /** How many Faster Payments are Stuck Payments now. */
  long count();

  /** Up to the limit of the Stuck Payments, oldest first. */
  List<StuckPayment> oldest(int limit);
}
