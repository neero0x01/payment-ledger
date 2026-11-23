/**
 * Read endpoints over the Read Model: the Account list and Statements. It reads the readmodel
 * database, which only the projector writes, through a read-only connection, and asks {@code
 * accounts} which Accounts a Principal may see. It also serves Read Model Balances to the balanced
 * check through posting's {@code ReadModelBalances} port.
 */
package com.paymentledger.ledger.views;
