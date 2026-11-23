package com.paymentledger.ledger.payments;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A Customer's instruction to move money out of one of their Customer Accounts. A Rejected Payment
 * carries its Rejection Reason; any other Payment has none. A Payment Rejected by a Scheme
 * Rejection also carries the scheme's reason code.
 */
public record Payment(
    PaymentId id,
    PaymentType type,
    PaymentStatus status,
    AccountId debtorAccount,
    Payee payee,
    Money amount,
    @Nullable RejectionReason rejectionReason,
    @Nullable SchemeReasonCode schemeReasonCode) {

  public Optional<RejectionReason> rejection() {
    return Optional.ofNullable(rejectionReason);
  }

  public Optional<SchemeReasonCode> schemeRejection() {
    return Optional.ofNullable(schemeReasonCode);
  }
}
