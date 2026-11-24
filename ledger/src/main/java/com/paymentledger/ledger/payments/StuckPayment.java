package com.paymentledger.ledger.payments;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.PaymentId;
import java.time.Instant;

/** A Faster Payment still Pending after its Scheme Outcome deadline, and when it was made. */
public record StuckPayment(
    PaymentId id,
    AccountId debtorAccount,
    Payee.AtAnotherBank payee,
    Money amount,
    Instant madeAt) {}
