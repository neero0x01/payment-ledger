package com.paymentledger.ledger.views.internal;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import com.paymentledger.ledger.shared.TransactionId;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the Read Model, which the projector builds from the ledger's Kafka events. */
@Repository
class JdbcReadModel {

  private final JdbcClient jdbc;

  JdbcReadModel(@Qualifier(ReadModelConnection.NAME) DataSource readModel) {
    this.jdbc = JdbcClient.create(readModel);
  }

  /** The Read Model Balance of each Account that has one; an Account with no Entries has none. */
  Map<AccountId, Money> balancesOf(Collection<AccountId> accounts) {
    if (accounts.isEmpty()) {
      return Map.of();
    }
    return jdbc
        .sql(
            """
            select account_id, balance_pence
            from account_balance_view
            where account_id in (:accountIds)
            """)
        .param("accountIds", accounts.stream().map(AccountId::value).toList())
        .query((rs, rowNum) ->
            Map.entry(
                new AccountId(rs.getObject("account_id", UUID.class)),
                Money.ofPence(rs.getLong("balance_pence"))))
        .list()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /** The Account's Statement Lines, newest first. */
  List<StatementLine> statementOf(AccountId account) {
    return jdbc.sql(
            """
            select transaction_id, booked_at, entry_seq, side, amount_pence,
                   balance_after_pence, counterparty_account_id
            from statement_line
            where account_id = :accountId
            order by booked_at desc, entry_seq desc
            """)
        .param("accountId", account.value())
        .query((rs, rowNum) -> {
          UUID counterparty = rs.getObject("counterparty_account_id", UUID.class);
          return new StatementLine(
              new TransactionId(rs.getObject("transaction_id", UUID.class)),
              rs.getObject("booked_at", OffsetDateTime.class).toInstant(),
              rs.getLong("entry_seq"),
              Side.valueOf(rs.getString("side")),
              Money.ofPence(rs.getLong("amount_pence")),
              Money.ofPence(rs.getLong("balance_after_pence")),
              counterparty == null ? null : new AccountId(counterparty));
        })
        .list();
  }
}
