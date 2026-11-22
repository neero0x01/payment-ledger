package com.paymentledger.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real Postgres 18; never H2. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:18");
  }

  /** Flyway migrates as the container's owner; the application then runs as ledger_app. */
  @Bean
  DynamicPropertyRegistrar flywayRunsAsTheSchemaOwner(PostgreSQLContainer postgres) {
    return registry -> {
      registry.add("spring.flyway.user", postgres::getUsername);
      registry.add("spring.flyway.password", postgres::getPassword);
    };
  }
}
