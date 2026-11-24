/**
 * Read endpoints over the Read Model: the Account list, Statements and the Operator dashboard. It
 * reads the readmodel database, which only the projector writes, through a read-only connection,
 * and asks {@code accounts} which Accounts a Principal may see. The dashboard adds the outbox lag
 * from {@code outbox}, the last balanced check from {@code posting}, the Stuck Payments from {@code
 * payments}, and the Projection Freshness, which compares the outbox's newest events with those the
 * Read Model has applied (ADR 0012). It also serves Read Model Balances to the balanced check
 * through posting's {@code ReadModelBalances} port.
 */
package com.paymentledger.ledger.views;
