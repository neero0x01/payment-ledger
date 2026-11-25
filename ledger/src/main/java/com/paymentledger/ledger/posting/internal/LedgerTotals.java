package com.paymentledger.ledger.posting.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The first job of the balanced check: that total Debits equal total Credits. */
@Component
class LedgerTotals {

  private final JdbcClient jdbc;

  LedgerTotals(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Σdebits − Σcredits over every Entry; zero in a Balanced Ledger. */
  long debitsMinusCredits() {
    return jdbc.sql(
            """
            select coalesce(sum(case when side = 'DEBIT' then amount_pence else -amount_pence end), 0)
            from entry
            """)
        .query(Long.class)
        .single();
  }
}
