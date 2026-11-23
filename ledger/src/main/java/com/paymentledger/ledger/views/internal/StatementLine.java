package com.paymentledger.ledger.views.internal;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import com.paymentledger.ledger.shared.TransactionId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One Entry as the Customer sees it: when it was booked, how much, which way, the counterparty and
 * the Balance after it. The counterparty is the Account on the other side of a two-Entry
 * Transaction, and null for one with more Entries.
 */
record StatementLine(
    TransactionId transaction,
    Instant bookingTime,
    long entrySeq,
    Side side,
    Money amount,
    Money balanceAfter,
    @Nullable AccountId counterparty) {}
