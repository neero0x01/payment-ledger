package com.paymentledger.ledger;

import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.accounts.Logins;
import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens the two Customer Accounts the bare transfer page shows when the ledger runs locally: A
 * funded with £100.00 from Funding by a real Transaction, and B empty. Their ids are fixed so the
 * page knows them; it runs once per database. Their Customer logs in as {@code local-customer} with
 * the password {@code local-customer}.
 */
@Component
@Profile("local")
class LocalDemoAccounts implements ApplicationRunner {

  static final AccountId ACCOUNT_A =
      new AccountId(UUID.fromString("019a0000-0000-7000-8000-00000000000a"));
  static final AccountId ACCOUNT_B =
      new AccountId(UUID.fromString("019a0000-0000-7000-8000-00000000000b"));
  private static final Money OPENING_BALANCE_OF_A = Money.ofPence(100_00);

  static final String LOCAL_CUSTOMER_LOGIN = "local-customer";

  private final Accounts accounts;
  private final Logins logins;
  private final Posting posting;

  LocalDemoAccounts(Accounts accounts, Logins logins, Posting posting) {
    this.accounts = accounts;
    this.logins = logins;
    this.posting = posting;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (accounts.find(ACCOUNT_A).isPresent()) {
      return;
    }
    CustomerId customer = accounts.registerCustomer("Local Demo Customer");
    logins.addCustomerLogin(customer, LOCAL_CUSTOMER_LOGIN, LOCAL_CUSTOMER_LOGIN);
    accounts.openCustomerAccount(ACCOUNT_A, customer, "Account A");
    accounts.openCustomerAccount(ACCOUNT_B, customer, "Account B");
    posting.post(
        NewTransaction.funding(
            List.of(
                new Entry(accounts.funding(), Side.DEBIT, OPENING_BALANCE_OF_A),
                new Entry(ACCOUNT_A, Side.CREDIT, OPENING_BALANCE_OF_A))));
  }
}
