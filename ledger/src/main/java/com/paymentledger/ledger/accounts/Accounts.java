package com.paymentledger.ledger.accounts;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import java.util.Optional;

/** Customers, their Customer Accounts and the Internal Accounts. */
public interface Accounts {

  CustomerId registerCustomer(String displayName);

  /** Opens a credit-normal Customer Account with a zero Balance. */
  AccountId openCustomerAccount(CustomerId owner, String name);

  /** The Funding Account, which money in Customer Accounts comes from in v1. */
  AccountId funding();

  Optional<Account> find(AccountId id);
}
