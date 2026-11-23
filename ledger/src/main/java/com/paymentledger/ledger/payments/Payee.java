package com.paymentledger.ledger.payments;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Problem;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Who a Payment pays: an Account at the fintech, or an external sort code, account number and name.
 */
public sealed interface Payee {

  /** The Payee of an Internal Transfer: another Account at the fintech. */
  record AtTheFintech(AccountId account) implements Payee {

    public AtTheFintech {
      Objects.requireNonNull(account, "account");
    }
  }

  /**
   * The Payee of a Faster Payment: an account at another UK bank, by its six-digit sort code,
   * eight-digit account number and name. Refuses any other shape as validation-failed.
   */
  record AtAnotherBank(String sortCode, String accountNumber, String name) implements Payee {

    private static final Pattern SORT_CODE = Pattern.compile("[0-9]{6}");
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("[0-9]{8}");

    public AtAnotherBank {
      if (sortCode == null || !SORT_CODE.matcher(sortCode).matches()) {
        throw Problem.validationFailed("The Payee's sort code must be six digits");
      }
      if (accountNumber == null || !ACCOUNT_NUMBER.matcher(accountNumber).matches()) {
        throw Problem.validationFailed("The Payee's account number must be eight digits");
      }
      if (name == null || name.isBlank()) {
        throw Problem.validationFailed("The Payee needs a name");
      }
    }
  }
}
