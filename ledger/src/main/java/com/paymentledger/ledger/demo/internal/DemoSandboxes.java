package com.paymentledger.ledger.demo.internal;

import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates Demo Sandboxes: a new Customer with two Customer Accounts, each funded from Funding by a
 * real Transaction. Sandboxes are never deleted.
 */
@Service
class DemoSandboxes {

  private static final Money MAIN_ACCOUNT_OPENING_BALANCE = Money.ofPence(1_000_00);
  private static final Money SAVINGS_ACCOUNT_OPENING_BALANCE = Money.ofPence(250_00);

  private final Accounts accounts;
  private final Posting posting;

  DemoSandboxes(Accounts accounts, Posting posting) {
    this.accounts = accounts;
    this.posting = posting;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  DemoSandbox create() {
    CustomerId customer = accounts.registerDemoCustomer("Demo Customer");
    AccountId main = open(customer, "Customer Account", MAIN_ACCOUNT_OPENING_BALANCE);
    AccountId savings = open(customer, "Savings Account", SAVINGS_ACCOUNT_OPENING_BALANCE);
    return new DemoSandbox(customer, List.of(main, savings));
  }

  private AccountId open(CustomerId customer, String name, Money openingBalance) {
    AccountId account = accounts.openCustomerAccount(customer, name, Money.ofPence(0));
    posting.post(
        NewTransaction.funding(
            List.of(
                new Entry(accounts.funding(), Side.DEBIT, openingBalance),
                new Entry(account, Side.CREDIT, openingBalance))));
    return account;
  }

  record DemoSandbox(CustomerId customer, List<AccountId> accounts) {}
}
