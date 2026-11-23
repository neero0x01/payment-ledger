package com.paymentledger.ledger.payments;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.util.Objects;

/**
 * A Faster Payment sent to the scheme for a decision: a slimmed-down ISO 20022 pacs.008 with the
 * End-to-End Id, the debtor's Account, the creditor at another bank and the amount.
 */
public record SchemeSubmission(
    EndToEndId endToEndId, AccountId debtorAccount, Payee.AtAnotherBank creditor, Money amount) {

  public SchemeSubmission {
    Objects.requireNonNull(endToEndId, "endToEndId");
    Objects.requireNonNull(debtorAccount, "debtorAccount");
    Objects.requireNonNull(creditor, "creditor");
    Objects.requireNonNull(amount, "amount");
  }
}
