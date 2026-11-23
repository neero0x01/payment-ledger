/**
 * Read endpoints over the Read Model: the Account list and Statements. It reads the readmodel
 * database, which only the projector writes, through a read-only connection, and asks {@code
 * accounts} which Accounts a Principal may see.
 */
package com.paymentledger.ledger.views;
