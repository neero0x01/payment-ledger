package com.paymentledger.ledger;

import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Arranges ledger state for tests through the modules' public APIs. v1 has no endpoint for opening
 * Accounts, so this is the only way in; assertions still go through the HTTP API.
 */
@Component
public class LedgerFixture {

  private final Accounts accounts;
  private final Posting posting;

  LedgerFixture(Accounts accounts, Posting posting) {
    this.accounts = accounts;
    this.posting = posting;
  }

  /** Opens a Customer Account for a new Customer, funded from Funding by a real Transaction. */
  public AccountId customerAccountWithBalance(Money balance) {
    CustomerId customer = accounts.registerCustomer("Test Customer");
    AccountId account = accounts.openCustomerAccount(customer, "Main");
    if (balance.isPositive()) {
      posting.post(
          NewTransaction.funding(
              List.of(
                  new Entry(accounts.funding(), Side.DEBIT, balance),
                  new Entry(account, Side.CREDIT, balance))));
    }
    return account;
  }
}
