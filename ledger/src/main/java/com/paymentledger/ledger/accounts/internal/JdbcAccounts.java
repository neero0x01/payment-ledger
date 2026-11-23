package com.paymentledger.ledger.accounts.internal;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.accounts.AccountKind;
import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Principal;
import com.paymentledger.ledger.shared.Side;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccounts implements Accounts {

  private final JdbcClient jdbc;

  JdbcAccounts(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public CustomerId registerCustomer(String displayName) {
    UUID id =
        jdbc.sql("insert into customer (display_name) values (:displayName) returning id")
            .param("displayName", displayName)
            .query(UUID.class)
            .single();
    return new CustomerId(id);
  }

  @Override
  public AccountId openCustomerAccount(CustomerId owner, String name, Money overdraftLimit) {
    UUID id =
        jdbc.sql(
                """
                insert into account (kind, customer_id, name, normal_side, overdraft_limit_pence)
                values ('CUSTOMER', :customerId, :name, 'CREDIT', :overdraftLimitPence)
                returning id
                """)
            .param("customerId", owner.value())
            .param("name", name)
            .param("overdraftLimitPence", overdraftLimit.pence())
            .query(UUID.class)
            .single();
    return new AccountId(id);
  }

  @Override
  public void openCustomerAccount(AccountId id, CustomerId owner, String name) {
    jdbc.sql(
            """
            insert into account (id, kind, customer_id, name, normal_side)
            values (:id, 'CUSTOMER', :customerId, :name, 'CREDIT')
            """)
        .param("id", id.value())
        .param("customerId", owner.value())
        .param("name", name)
        .update();
  }

  @Override
  public AccountId funding() {
    UUID id =
        jdbc.sql("select id from account where kind = 'INTERNAL' and name = 'Funding'")
            .query(UUID.class)
            .single();
    return new AccountId(id);
  }

  @Override
  public AccountId corrections() {
    UUID id =
        jdbc.sql("select id from account where kind = 'INTERNAL' and name = 'Corrections'")
            .query(UUID.class)
            .single();
    return new AccountId(id);
  }

  @Override
  public Optional<Account> find(AccountId id) {
    return jdbc.sql(
            """
            select id, name, kind, normal_side, overdraft_limit_pence, customer_id
            from account
            where id = :id
            """)
        .param("id", id.value())
        .query(JdbcAccounts::account)
        .optional();
  }

  @Override
  public List<Account> visibleTo(Principal principal) {
    return principal
        .customerId()
        .map(customer ->
            jdbc.sql(
                    """
                    select id, name, kind, normal_side, overdraft_limit_pence, customer_id
                    from account
                    where customer_id = :customerId
                    order by created_at, id
                    """)
                .param("customerId", customer.value())
                .query(JdbcAccounts::account)
                .list())
        .orElseGet(() ->
            jdbc.sql(
                    """
                    select id, name, kind, normal_side, overdraft_limit_pence, customer_id
                    from account
                    order by created_at, id
                    """)
                .query(JdbcAccounts::account)
                .list());
  }

  private static Account account(ResultSet rs, int rowNum) throws SQLException {
    UUID owner = rs.getObject("customer_id", UUID.class);
    return new Account(
        new AccountId(rs.getObject("id", UUID.class)),
        rs.getString("name"),
        AccountKind.valueOf(rs.getString("kind")),
        Side.valueOf(rs.getString("normal_side")),
        Money.ofPence(rs.getLong("overdraft_limit_pence")),
        owner == null ? null : new CustomerId(owner));
  }
}
