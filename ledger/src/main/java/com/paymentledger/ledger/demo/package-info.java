/**
 * The public demo (spec §8): one click starts a session as the Customer of a new Demo Sandbox, or
 * as the demo Operator. It opens and funds the sandbox through {@code accounts} and {@code
 * posting}, which is why it is a module of its own: {@code posting} already depends on {@code
 * accounts}, so sandbox funding cannot live in {@code accounts} without a cycle.
 */
package com.paymentledger.ledger.demo;
