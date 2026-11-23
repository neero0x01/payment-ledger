package com.paymentledger.ledger;

import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Scenario 10: an event the projector reads while the readmodel database is unavailable is applied
 * once the database is back, never dropped. The test makes the readmodel database refuse
 * connections, so the context has its own Postgres and Kafka, closed after the class. Its projector
 * gives up waiting for a connection after 250 ms instead of 30 s, so each failed attempt is quick.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({DedicatedContainersConfiguration.class, LedgerFixture.class, Sessions.class})
@DirtiesContext
class ReadModelOutageTest {

  private static final Duration PROJECTION = Duration.ofSeconds(60);

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired PostgreSQLContainer postgres;
  @Autowired KafkaContainer kafka;

  @Test
  void an_event_read_while_the_read_model_database_is_down_is_applied_once_it_is_back()
      throws Exception {
    try (ConfigurableApplicationContext projector =
        ProjectorConfiguration.run(
            postgres, kafka, "--spring.datasource.hikari.connection-timeout=250")) {
      AccountId a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
      AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));
      RestTestClient payer = sessions.customerOwning(a);
      RestTestClient payee = sessions.customerOwning(b);
      awaitReadModelBalance(payer, 100_00);

      readModelDatabaseAcceptsConnections(false);
      try {
        BalancedCheckTest.transfer(payer, a, b, 10_00);
        // Long enough for the projector to fail on the event many times over (each attempt waits
        // about 500 ms for a connection).
        Thread.sleep(10_000);
      } finally {
        readModelDatabaseAcceptsConnections(true);
      }

      awaitReadModelBalance(payee, 10_00);
      awaitReadModelBalance(payer, 90_00);
    }
  }

  private static void awaitReadModelBalance(RestTestClient customer, long balancePence) {
    await()
        .atMost(PROJECTION)
        .ignoreExceptions()
        .until(() ->
            ReadEndpoints.accounts(customer).get(0).get("balancePence").asLong() == balancePence);
  }

  /**
   * Makes the readmodel database refuse new connections and closes the open ones, as an outage
   * does, or lets it accept connections again.
   */
  private void readModelDatabaseAcceptsConnections(boolean accepts) throws SQLException {
    try (Connection admin =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement statement = admin.createStatement()) {
      statement.execute("alter database readmodel with allow_connections " + accepts);
      if (!accepts) {
        statement.execute(
            "select pg_terminate_backend(pid) from pg_stat_activity where datname = 'readmodel'");
      }
    }
  }
}
