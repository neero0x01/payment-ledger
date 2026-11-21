package com.paymentledger.ledger.shared;

/**
 * What a logged-in Principal may do: a Customer acts on their own Accounts; an Operator watches
 * over the ledger.
 */
public enum Role {
  CUSTOMER,
  OPERATOR
}
