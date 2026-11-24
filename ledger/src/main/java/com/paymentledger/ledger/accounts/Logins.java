package com.paymentledger.ledger.accounts;

import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.OperatorId;

/**
 * Who may log in to the ledger: Customers, who act on their own Customer Accounts, and Operators.
 * Passwords are stored only as hashes.
 */
public interface Logins {

  /** Lets the Customer log in with the username and password. */
  void addCustomerLogin(CustomerId customer, String username, String password);

  /** Lets an Operator log in with the username and password. */
  void addOperatorLogin(String username, String password);

  /**
   * The demo Operator every "Try as operator" visitor acts as, created the first time it is asked
   * for. Nobody can log in to it with a password.
   */
  OperatorId demoOperator();

  /** Whether the Operator is the demo Operator, who corrects only Demo Sandbox Accounts. */
  boolean isDemoOperator(OperatorId operator);
}
