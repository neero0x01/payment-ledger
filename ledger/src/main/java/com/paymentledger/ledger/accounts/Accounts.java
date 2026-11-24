package com.paymentledger.ledger.accounts;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Principal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Customers, their Customer Accounts and the Internal Accounts. */
public interface Accounts {

  CustomerId registerCustomer(String displayName);

  /** Registers the Customer of a new Demo Sandbox, for one visitor to the public demo. */
  CustomerId registerDemoCustomer(String displayName);

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
   * Whether the Accounts include a Customer Account and every Customer Account among them is a
   * seeded one, which the seed loaded and no Correction may touch (spec §8). Internal Accounts
   * don't count: seeded and live data share them.
   */
  boolean areSeededOnly(Collection<AccountId> accounts);

  /** Whether the Account is a Customer Account in a Demo Sandbox. */
  boolean isInDemoSandbox(AccountId id);

  /**
   * The Accounts the Principal may see, oldest first: a Customer's own Customer Accounts, or every
   * Account for an Operator.
   */
  List<Account> visibleTo(Principal principal);
}
