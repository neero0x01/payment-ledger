package com.paymentledger.ledger;

import static com.paymentledger.ledger.ReadEndpoints.accounts;
import static com.paymentledger.ledger.ReadEndpoints.statementLines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.TransactionId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scenario 10 [M4, completed in M5]: Payments keep succeeding while Kafka is down, the outbox lag
 * gauge shows the events waiting, they are all published once Kafka is back, and the Read Model
 * then holds exactly those Transactions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({
  RestartableKafkaConfiguration.class,
  ProjectorConfiguration.class,
  LedgerFixture.class,
  Sessions.class,
  TransactionPostedEvents.class
})
class KafkaOutageTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;
  @Autowired TransactionPostedEvents events;
  @Autowired KafkaContainer kafka;

  @Test
  void payments_succeed_while_kafka_is_stopped_and_the_read_model_catches_up_once_it_restarts() {
    AccountId a = ledger.customerAccountWithBalance(Money.ofPence(1_000_00));
    AccountId b = ledger.customerAccountWithBalance(Money.ofPence(0));
    RestTestClient customer = sessions.customerOwning(a);
    RestTestClient operator = sessions.operator();
    await().atMost(Duration.ofSeconds(30)).until(() -> unpublished(operator) == 0);

    RestartableKafkaConfiguration.stop(kafka);
    List<TransactionId> transactions = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      transactions.add(transfer(customer, a, b, 1_00));
    }

    assertThat(transactions).hasSize(100);
    // Each Payment writes two events: its Transaction and its status change.
    assertThat(unpublished(operator)).isEqualTo(200);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(oldestAgeSeconds(operator)).isPositive());

    RestartableKafkaConfiguration.start(kafka);

    await().atMost(Duration.ofSeconds(90)).until(() -> unpublished(operator) == 0);
    assertThat(oldestAgeSeconds(operator)).isZero();
    List<String> published =
        events.publishedForAll(transactions).stream().map(ConsumerRecord::key).toList();
    assertThat(published)
        .containsAll(transactions.stream().map(id -> id.value().toString()).toList());

    List<String> projected =
        await()
            .atMost(Duration.ofSeconds(60))
            .until(() -> statementLines(operator, b), lines -> lines.size() >= 100)
            .stream()
            .map(line -> line.get("transactionId").asString())
            .toList();
    assertThat(projected)
        .containsExactlyInAnyOrderElementsOf(
            transactions.stream().map(id -> id.value().toString()).toList());
    assertThat(readModelBalancePence(customer, a)).isEqualTo(900_00);
  }

  private static long readModelBalancePence(RestTestClient customer, AccountId account) {
    for (JsonNode listed : accounts(customer)) {
      if (listed.get("id").asString().equals(account.value().toString())) {
        return listed.get("balancePence").asLong();
      }
    }
    throw new AssertionError("GET /v1/accounts does not list " + account.value());
  }

  private static TransactionId transfer(
      RestTestClient customer, AccountId from, AccountId to, long amountPence) {
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
    return new TransactionId(
        UUID.fromString(JSON.readTree(body).get("transactionIds").get(0).asString()));
  }

  /** The outbox lag gauge: how many events are waiting to be published. */
  private static long unpublished(RestTestClient operator) {
    return (long) gauge(operator, "ledger.outbox.unpublished");
  }

  /** The outbox lag gauge: how long the oldest waiting event has waited. */
  private static double oldestAgeSeconds(RestTestClient operator) {
    return gauge(operator, "ledger.outbox.oldest.age");
  }

  private static double gauge(RestTestClient operator, String name) {
    String body =
        operator
            .get()
            .uri("/actuator/metrics/{name}", name)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    JsonNode metric = JSON.readTree(body);
    return metric.get("measurements").get(0).get("value").asDouble();
  }
}
