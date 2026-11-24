package com.paymentledger.ledger.accounts.internal;

import com.paymentledger.ledger.accounts.Logins;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.OperatorId;
import com.paymentledger.ledger.shared.Principal;
import com.paymentledger.ledger.shared.Role;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLogins implements Logins {

  /** Hashes with bcrypt, and names the algorithm in the hash so it can change later. */
  static final PasswordEncoder PASSWORDS =
      PasswordEncoderFactories.createDelegatingPasswordEncoder();

  private static final String DEMO_OPERATOR = "demo-operator";

  private final JdbcClient jdbc;

  JdbcLogins(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void addCustomerLogin(CustomerId customer, String username, String password) {
    insert(username, password, Role.CUSTOMER, customer.value());
  }

  @Override
  public void addOperatorLogin(String username, String password) {
    insert(username, password, Role.OPERATOR, null);
  }

  @Override
  public OperatorId demoOperator() {
    // A random password nobody knows: the demo Operator is reached only through a demo session.
    jdbc.sql(
            """
            insert into login (username, password_hash, role, demo)
            values (:username, :passwordHash, 'OPERATOR', true)
            on conflict (username) do nothing
            """)
        .param("username", DEMO_OPERATOR)
        .param("passwordHash", PASSWORDS.encode(UUID.randomUUID().toString()))
        .update();
    UUID id =
        jdbc.sql("select id from login where username = :username and demo")
            .param("username", DEMO_OPERATOR)
            .query(UUID.class)
            .single();
    return new OperatorId(id);
  }

  @Override
  public boolean isDemoOperator(OperatorId operator) {
    return jdbc.sql("select demo from login where id = :id")
        .param("id", operator.value())
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }

  Optional<StoredLogin> find(String username) {
    return jdbc.sql(
            "select id, password_hash, role, customer_id from login where username = :username")
        .param("username", username)
        .query((rs, rowNum) -> {
          Role role = Role.valueOf(rs.getString("role"));
          UUID customer = rs.getObject("customer_id", UUID.class);
          Principal principal =
              role == Role.CUSTOMER
                  ? Principal.customer(new CustomerId(customer))
                  : Principal.operator(new OperatorId(rs.getObject("id", UUID.class)));
          return new StoredLogin(rs.getString("password_hash"), principal);
        })
        .optional();
  }

  private void insert(String username, String password, Role role, @Nullable UUID customer) {
    jdbc.sql(
            """
            insert into login (username, password_hash, role, customer_id)
            values (:username, :passwordHash, :role, :customerId)
            """)
        .param("username", username)
        .param("passwordHash", PASSWORDS.encode(password))
        .param("role", role.name())
        .param("customerId", customer, Types.OTHER)
        .update();
  }

  record StoredLogin(String passwordHash, Principal principal) {}
}
