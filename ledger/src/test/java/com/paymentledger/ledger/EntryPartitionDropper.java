package com.paymentledger.ledger;

import java.time.YearMonth;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drops an empty future Entry partition, as only a test may: the ledger never drops partitions (ADR
 * 0010). It drops it as the schema owner, which owns them, so use it only on a context with a
 * Postgres of its own.
 */
@Component
public class EntryPartitionDropper {

  private final JdbcClient jdbc;
  private final TransactionTemplate transaction;

  EntryPartitionDropper(JdbcClient jdbc, TransactionTemplate transaction) {
    this.jdbc = jdbc;
    this.transaction = transaction;
  }

  /** Drops the partition of the month this many months after the current one, in UTC. */
  public void dropMonthsAhead(int monthsAhead) {
    YearMonth month = YearMonth.now(ZoneOffset.UTC).plusMonths(monthsAhead);
    String partition = "entry_%04d_%02d".formatted(month.getYear(), month.getMonthValue());
    transaction.executeWithoutResult(status -> {
      jdbc.sql("reset role").update();
      jdbc.sql("drop table " + partition).update();
      jdbc.sql("set role ledger_app").update();
    });
  }
}
