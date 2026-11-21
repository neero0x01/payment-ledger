package com.paymentledger.ledger.payments;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;

/** A Customer's instruction to move money out of one of their Customer Accounts. */
public record Payment(
    PaymentId id,
    PaymentType type,
    PaymentStatus status,
    AccountId debtorAccount,
    AccountId payeeAccount,
    Money amount) {}
