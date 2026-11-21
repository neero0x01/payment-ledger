/**
 * Value types every ledger module speaks in: ids, {@link com.paymentledger.ledger.shared.Money},
 * {@link com.paymentledger.ledger.shared.Side} and the {@link
 * com.paymentledger.ledger.shared.ProblemType} catalogue with the {@link
 * com.paymentledger.ledger.shared.Problem} every module throws for a broken request. Plain Java
 * with no behaviour beyond the values; {@code internal} answers Problems over HTTP.
 */
package com.paymentledger.ledger.shared;
