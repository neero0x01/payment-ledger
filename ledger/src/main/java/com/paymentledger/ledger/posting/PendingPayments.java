package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.PaymentId;

/**
 * Tells posting whether a Payment still awaits its final outcome, so that no Correction touches a
 * Transaction whose Payment is Pending: a late Reversal could then refund the Customer twice. The
 * payments module implements it, since posting must not depend on payments.
 */
public interface PendingPayments {

  /** Whether the Payment is Pending. */
  boolean isPending(PaymentId payment);
}
