package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.AccountId;
import java.util.Collection;
import java.util.Map;

/**
 * The port the balanced check reads Read Model Balances through, to compare them with the ledger's.
 * The views module, which alone reads the Read Model, implements it.
 */
public interface ReadModelBalances {

  /** The Read Model Balance of each of the Accounts that has one. */
  Map<AccountId, ReadModelBalance> readModelBalancesOf(Collection<AccountId> accounts);
}
