package com.paymentledger.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.LedgerApplication;
import com.paymentledger.ledger.accounts.Logins;
import com.paymentledger.projector.ProjectorApplication;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;

/**
 * Spec §10: the seed CLI bulk-loads its Transactions into an empty ledger database, marked
 * source=SEED; the running ledger's outbox publisher drains their events into Kafka, the projector
 * builds the Read Model from them, and the balanced check finds the ledger balanced. The seed runs
 * before the ledger has ever started, so it migrates the database itself. Real Postgres 18 and real
 * Kafka, of this class's own, on random ports.
 */
class SeedLoadTest {

  private static final int CUSTOMERS = 20;
  private static final String OPERATOR = "seed-test-operator";
  private static final String PASSWORD = "correct horse battery staple";
  private static final String LOAD_TEST_PASSWORD = "load test " + UUID.randomUUID();
  private static final Duration DRAINED = Duration.ofSeconds(90);

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18")
          .withCommand("postgres", "-c", "fsync=off")
          .withCopyFileToContainer(
              MountableFile.forHostPath("../infra/postgres/initdb/readmodel.sql"),
              "/docker-entrypoint-initdb.d/readmodel.sql");
  private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

  private static ConfigurableApplicationContext ledger;
  private static ConfigurableApplicationContext projector;
  private static SessionClient operator;
  private static URI base;

  @BeforeAll
  static void seedThenStartTheLedgerAndProjector() {
    Startables.deepStart(POSTGRES, KAFKA).join();

    SeedCommand.run(seedArguments());

    ledger =
        new SpringApplicationBuilder(LedgerApplication.class)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.flyway.user=" + POSTGRES.getUsername(),
                "--spring.flyway.password=" + POSTGRES.getPassword(),
                "--ledger.read-model.datasource.url=" + readModelUrl(),
                "--ledger.read-model.datasource.username=" + POSTGRES.getUsername(),
                "--ledger.read-model.datasource.password=" + POSTGRES.getPassword(),
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--ledger.balanced-check.interval=PT1S");
    projector =
        ProjectorApplication.application()
            .run(
                "--spring.datasource.url=" + readModelUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers());
    ledger.getBean(Logins.class).addOperatorLogin(OPERATOR, PASSWORD);
    base =
        URI.create(
            "http://localhost:" + ledger.getEnvironment().getRequiredProperty("local.server.port"));
    operator = SessionClient.logIn(base, OPERATOR, PASSWORD);
  }

  @AfterAll
  static void stop() {
    if (projector != null) {
      projector.close();
    }
    if (ledger != null) {
      ledger.close();
    }
    KAFKA.stop();
    POSTGRES.stop();
  }

  @Test
  void read_model_matches_the_ledger_once_the_outbox_drains() {
    List<JsonNode> accounts = list(operator.get("/v1/accounts"));
    assertThat(accounts).hasSizeGreaterThanOrEqualTo(CUSTOMERS + 200);

    awaitReadModelMatchesTheLedger();

    assertThat(list(operator.get("/v1/accounts")))
        .filteredOn(account -> account.get("kind").asString().equals("CUSTOMER"))
        .anyMatch(account -> account.get("balancePence").asLong() != 0);
  }

  @Test
  void ledger_is_balanced_once_the_read_model_has_caught_up() {
    awaitReadModelMatchesTheLedger();
    Instant caughtUp = Instant.now();

    await()
        .atMost(DRAINED)
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> lastBalancedCheckRun().isAfter(caughtUp) && gauge("ledger.balanced") == 1.0);

    assertThat(gauge("ledger.imbalance.pence")).isZero();
  }

  @Test
  void seeded_transactions_are_marked_seed() {
    JsonNode loadTestAccount =
        list(operator.get("/v1/accounts")).stream()
            .filter(account -> account.get("name").asString().equals("Load Test Account 001"))
            .findFirst()
            .orElseThrow();
    String accountId = loadTestAccount.get("id").asString();
    List<JsonNode> lines =
        await()
            .atMost(DRAINED)
            .until(
                () -> list(operator.get("/v1/accounts/" + accountId + "/statement").get("lines")),
                l -> !l.isEmpty());

    JsonNode transaction =
        operator.get("/v1/transactions/" + lines.getFirst().get("transactionId").asString());

    assertThat(transaction.get("kind").asString()).isEqualTo("FUNDING");
    assertThat(transaction.get("source").asString()).isEqualTo("SEED");
    assertThat(Instant.parse(transaction.get("bookingTime").asString()))
        .isEqualTo(Instant.parse("2025-11-01T00:01:00Z"));
  }

  @Test
  void correction_of_a_seeded_transaction_is_refused_as_seed_data_read_only() {
    String accountId = loadTestAccountId();
    String seeded = firstTransactionOn(accountId);
    long before = balancePenceOf(accountId);

    SessionClient.Response refused =
        operator.post(
            "/v1/corrections",
            """
            {"kind": "REVERSING", "transactionId": "%s", "reason": "DUPLICATE_POSTING",
             "note": "Seeded data is read-only"}
            """
                .formatted(seeded));

    assertThat(refused.status()).isEqualTo(409);
    assertThat(refused.body().get("type").asString()).isEqualTo("/problems/seed-data-read-only");
    assertThat(balancePenceOf(accountId)).isEqualTo(before);
  }

  @Test
  void adjustment_of_a_seeded_customer_account_is_refused_as_seed_data_read_only() {
    String accountId = loadTestAccountId();
    long before = balancePenceOf(accountId);

    SessionClient.Response refused =
        operator.post(
            "/v1/corrections",
            """
            {"kind": "ADJUSTMENT", "reason": "GOODWILL", "note": "Seeded data is read-only",
             "accountId": "%s", "amountPence": 500, "direction": "CREDIT"}
            """
                .formatted(accountId));

    assertThat(refused.status()).isEqualTo(409);
    assertThat(refused.body().get("type").asString()).isEqualTo("/problems/seed-data-read-only");
    assertThat(balancePenceOf(accountId)).isEqualTo(before);
  }

  @Test
  void load_test_customer_logs_in_and_pays_from_their_load_test_account() {
    String from = accountIdNamed("Load Test Account 200");
    String to = accountIdNamed("Load Test Account 199");
    SessionClient customer = SessionClient.logIn(base, "load-test-200", LOAD_TEST_PASSWORD);

    SessionClient.Response payment =
        customer.post(
            "/v1/payments",
            """
            {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": 100}
            """
                .formatted(from, to));

    assertThat(payment.status()).isEqualTo(201);
    assertThat(payment.body().get("status").asString()).isEqualTo("COMPLETED");
  }

  @Test
  void seeding_a_ledger_that_already_has_transactions_is_refused() {
    String someAccount = list(operator.get("/v1/accounts")).getFirst().get("id").asString();
    long before =
        operator.get("/v1/accounts/" + someAccount + "/balance").get("balancePence").asLong();

    assertThatThrownBy(() -> SeedCommand.run(seedArguments()))
        .isInstanceOf(SeedRefused.class)
        .hasMessageContaining("already has Transactions");

    assertThat(
            operator.get("/v1/accounts/" + someAccount + "/balance").get("balancePence").asLong())
        .isEqualTo(before);
    assertThat(list(operator.get("/v1/accounts")))
        .filteredOn(account -> account.get("name").asString().equals("Load Test Account 001"))
        .hasSize(1);
  }

  private static String loadTestAccountId() {
    return accountIdNamed("Load Test Account 001");
  }

  private static String accountIdNamed(String name) {
    return list(operator.get("/v1/accounts")).stream()
        .filter(account -> account.get("name").asString().equals(name))
        .findFirst()
        .orElseThrow()
        .get("id")
        .asString();
  }

  /** The Transaction of the Account's first Statement Line, once the Read Model has it. */
  private static String firstTransactionOn(String accountId) {
    return await()
        .atMost(DRAINED)
        .until(
            () -> list(operator.get("/v1/accounts/" + accountId + "/statement").get("lines")),
            l -> !l.isEmpty())
        .getFirst()
        .get("transactionId")
        .asString();
  }

  private static long balancePenceOf(String accountId) {
    return operator.get("/v1/accounts/" + accountId + "/balance").get("balancePence").asLong();
  }

  /** Waits until every Account's Read Model Balance equals its Balance in the ledger. */
  private static void awaitReadModelMatchesTheLedger() {
    await()
        .atMost(DRAINED)
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> {
          for (JsonNode account : list(operator.get("/v1/accounts"))) {
            String id = account.get("id").asString();
            long ledgerBalance =
                operator.get("/v1/accounts/" + id + "/balance").get("balancePence").asLong();
            assertThat(account.get("balancePence").asLong())
                .as("Read Model Balance of %s", account.get("name").asString())
                .isEqualTo(ledgerBalance);
          }
        });
  }

  private static String[] seedArguments() {
    return new String[] {
      "--url=" + POSTGRES.getJdbcUrl(),
      "--user=" + POSTGRES.getUsername(),
      "--password=" + POSTGRES.getPassword(),
      "--customers=" + CUSTOMERS,
      "--load-test-password=" + LOAD_TEST_PASSWORD
    };
  }

  private static double gauge(String name) {
    return operator
        .get("/actuator/metrics/" + name)
        .get("measurements")
        .get(0)
        .get("value")
        .asDouble();
  }

  private static Instant lastBalancedCheckRun() {
    double epochSeconds = gauge("ledger.balanced.last.run");
    return Instant.ofEpochMilli((long) (epochSeconds * 1000));
  }

  private static List<JsonNode> list(JsonNode array) {
    List<JsonNode> items = new ArrayList<>();
    array.forEach(items::add);
    return items;
  }

  private static String readModelUrl() {
    return "jdbc:postgresql://%s:%d/readmodel"
        .formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
  }
}
