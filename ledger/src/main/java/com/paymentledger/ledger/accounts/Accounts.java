package com.paymentledger.ledger.accounts;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.Money;
import java.util.Optional;

/** Customers, their Customer Accounts and the Internal Accounts. */
public interface Accounts {

  CustomerId registerCustomer(String displayName);

  /** Opens a credit-normal Customer Account with a zero Balance and the given Overdraft Limit. */
  AccountId openCustomerAccount(CustomerId owner, String name, Money overdraftLimit);

  /** Opens a credit-normal Customer Account with a zero Balance under an id the caller chose. */
  void openCustomerAccount(AccountId id, CustomerId owner, String name);

  /** The Funding Account, which money in Customer Accounts comes from in v1. */
  AccountId funding();

  /** The Corrections Account, on the other side of every Adjustment. */
  AccountId corrections();

  Optional<Account> find(AccountId id);
}
