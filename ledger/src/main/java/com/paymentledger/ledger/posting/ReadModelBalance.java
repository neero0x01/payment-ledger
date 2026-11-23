package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.Money;

/**
 * An Account's Balance in the Read Model, as of the Entry with the highest entry_seq the projector
 * has applied to it.
 */
public record ReadModelBalance(Money balance, long lastEntrySeq) {}
