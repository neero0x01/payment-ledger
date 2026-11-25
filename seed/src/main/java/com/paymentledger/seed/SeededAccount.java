package com.paymentledger.seed;

import com.paymentledger.ledger.accounts.Account;
import java.time.Instant;
import java.util.Objects;

/**
 * A synthetic Customer Account, known to other banks by its sort code and account number. A
 * load-test Account is one the load test pays between.
 */
public record SeededAccount(
    Account account, String sortCode, String accountNumber, boolean loadTest, Instant createdAt) {

  public SeededAccount {
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(sortCode, "sortCode");
    Objects.requireNonNull(accountNumber, "accountNumber");
    Objects.requireNonNull(createdAt, "createdAt");
    if (!account.isCustomerAccount()) {
      throw new IllegalArgumentException("The seed opens only Customer Accounts");
    }
  }
}
