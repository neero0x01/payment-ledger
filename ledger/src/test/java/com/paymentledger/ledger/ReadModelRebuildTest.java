package com.paymentledger.ledger;

import static com.paymentledger.ledger.ReadEndpoints.accounts;
import static com.paymentledger.ledger.ReadEndpoints.statementLines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Scenario 10 [M5]: an Operator rebuilds the Read Model by replaying Kafka with {@code projector
 * --rebuild}, and the rebuilt Read Model equals the live one. Each test stops or starts the
 * projector, so each gets its own context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({
  TestcontainersConfiguration.class,
  ProjectorConfiguration.class,
  LedgerFixture.class,
  Sessions.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ReadModelRebuildTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Duration PROJECTION = Duration.ofSeconds(60);

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired PostgreSQLContainer postgres;
  @Autowired KafkaContainer kafka;

  @Autowired
  @Qualifier(ProjectorConfiguration.PROJECTOR)
  ConfigurableApplicationContext projector;

  @Test
  void rebuilt_read_model_equals_the_live_one() {
    RestTestClient operator = sessions.operator();
    postPaymentsAndACorrection(operator);
    Map<String, JsonNode> live = readModel(operator);

    projector.close();
    breakReadModel();
    ProjectorConfiguration.run(postgres, kafka, "--rebuild").close();

    assertThat(readModel(operator)).isEqualTo(live);
  }

  @Test
  void rebuild_is_refused_while_the_projector_is_consuming() {
    RestTestClient operator = sessions.operator();
    postPaymentsAndACorrection(operator);
    Map<String, JsonNode> live = readModel(operator);

    assertThatThrownBy(() -> ProjectorConfiguration.run(postgres, kafka, "--rebuild"))
        .rootCause()
        .hasMessageContaining("Stop the projector");
    assertThat(readModel(operator)).isEqualTo(live);
  }

  /**
   * Payments between three Customer Accounts and a Reversing Correction of one of them, once the
   * live Read Model shows them all: A has its funding, 7 Payments and the Correction; B 5 Payments
   * in, 1 out and the Correction; C 3 Payments in.
   */
  private void postPaymentsAndACorrection(RestTestClient operator) {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(1_000_00));
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));
    AccountId c = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient payer = sessions.customerOwning(a);
    RestTestClient payee = sessions.customerOwning(b);
    UUID mistake = pay(payer, a, b, 10_00);
    for (int i = 0; i < 4; i++) {
      pay(payer, a, b, 1_00 + i);
    }
    pay(payer, a, c, 25_00);
    pay(payer, a, c, 2_50);
    pay(payee, b, c, 3_00);
    reverse(operator, mistake);
    awaitStatementLines(operator, a, 9);
    awaitStatementLines(operator, b, 7);
    awaitStatementLines(operator, c, 3);
  }

  /** Every Account the Operator can see, by id, with its Statement Lines. */
  private static Map<String, JsonNode> readModel(RestTestClient operator) {
    Map<String, JsonNode> readModel = new TreeMap<>();
    for (JsonNode account : accounts(operator)) {
      AccountId id = new AccountId(UUID.fromString(account.get("id").asString()));
      ObjectNode view = JSON.createObjectNode();
      view.set("account", account);
      view.set("lines", JSON.valueToTree(statementLines(operator, id)));
      readModel.put(id.value().toString(), view);
    }
    return readModel;
  }

  /** A broken projection, as when a bug in the projector wrote nothing, or garbage. */
  private void breakReadModel() {
    DriverManagerDataSource readModel =
        new DriverManagerDataSource(
            PostgresTestcontainer.readModelUrl(postgres),
            postgres.getUsername(),
            postgres.getPassword());
    JdbcClient.create(readModel)
        .sql("truncate account_balance_view, statement_line, processed_event")
        .update();
  }

  private static UUID pay(RestTestClient customer, AccountId from, AccountId to, long amountPence) {
    String body =
        customer
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {"debtorAccountId": "%s", "payee": {"accountId": "%s"}, "amountPence": %d}
                """
                    .formatted(from.value(), to.value(), amountPence))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    return UUID.fromString(JSON.readTree(body).get("transactionIds").get(0).asString());
  }

  private static void reverse(RestTestClient operator, UUID transaction) {
    operator
        .post()
        .uri("/v1/corrections")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {"kind": "REVERSING", "transactionId": "%s", "reason": "DUPLICATE_POSTING",
             "note": "Paid twice by mistake"}
            """
                .formatted(transaction))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED);
  }

  private static List<JsonNode> awaitStatementLines(
      RestTestClient client, AccountId account, int count) {
    return await()
        .atMost(PROJECTION)
        .until(() -> statementLines(client, account), lines -> lines.size() >= count);
  }
}
