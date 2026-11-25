package com.paymentledger.fpssimulator;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real Postgres 18 for the simulator's own database, shared by every test context in the JVM, and
 * a {@link LedgerStub} per context that receives its Scheme Outcomes.
 */
@TestConfiguration(proxyBeanMethods = false)
class SimulatorTestConfiguration {

  static final String HMAC_KEY = "test-scheme-callback-key";

  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18").withCommand("postgres", "-c", "fsync=off");

  static {
    POSTGRES.start();
  }

  @Bean
  LedgerStub ledgerStub() {
    return new LedgerStub();
  }

  @Bean
  DynamicPropertyRegistrar simulatorConnections(LedgerStub ledger) {
    return registry -> {
      registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
      registry.add("spring.datasource.username", POSTGRES::getUsername);
      registry.add("spring.datasource.password", POSTGRES::getPassword);
      registry.add("FPSSIM_LEDGER_BASE_URL", ledger::baseUrl);
      registry.add("FPSSIM_CALLBACK_HMAC_KEY", () -> HMAC_KEY);
    };
  }
}
