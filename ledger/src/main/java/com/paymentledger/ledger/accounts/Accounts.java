package com.paymentledger.ledger.accounts;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Principal;
import java.util.List;
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

  /** FPS Clearing, the Clearing Account that Faster Payments wait in. */
  AccountId fpsClearing();

  Optional<Account> find(AccountId id);

  /**
   * The Accounts the Principal may see, oldest first: a Customer's own Customer Accounts, or every
   * Account for an Operator.
   */
  List<Account> visibleTo(Principal principal);
}
