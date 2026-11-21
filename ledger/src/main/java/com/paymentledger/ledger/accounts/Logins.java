package com.paymentledger.ledger.accounts;

import com.paymentledger.ledger.shared.CustomerId;

/**
 * Who may log in to the ledger: Customers, who act on their own Customer Accounts, and Operators.
 * Passwords are stored only as hashes.
 */
public interface Logins {

  /** Lets the Customer log in with the username and password. */
  void addCustomerLogin(CustomerId customer, String username, String password);

  /** Lets an Operator log in with the username and password. */
  void addOperatorLogin(String username, String password);
}
