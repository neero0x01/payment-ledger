package com.paymentledger.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Real Postgres 18; never H2. It also holds the empty {@code readmodel} database, created by the
 * same init script as the local stack's.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

  private static final String READ_MODEL_INIT_SCRIPT = "../infra/postgres/initdb/readmodel.sql";

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:18")
        .withCopyFileToContainer(
            MountableFile.forHostPath(READ_MODEL_INIT_SCRIPT),
            "/docker-entrypoint-initdb.d/readmodel.sql");
  }

  /**
   * Flyway migrates as the container's owner; the application then runs as ledger_app. The views
   * module reads the readmodel database.
   */
  @Bean
  DynamicPropertyRegistrar flywayRunsAsTheSchemaOwner(PostgreSQLContainer postgres) {
    return registry -> {
      registry.add("spring.flyway.user", postgres::getUsername);
      registry.add("spring.flyway.password", postgres::getPassword);
      registry.add("ledger.read-model.datasource.url", () -> readModelUrl(postgres));
      registry.add("ledger.read-model.datasource.username", postgres::getUsername);
      registry.add("ledger.read-model.datasource.password", postgres::getPassword);
    };
  }

  /** The JDBC URL of the readmodel database in the container. */
  static String readModelUrl(PostgreSQLContainer postgres) {
    return "jdbc:postgresql://%s:%d/readmodel"
        .formatted(postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
  }
}
