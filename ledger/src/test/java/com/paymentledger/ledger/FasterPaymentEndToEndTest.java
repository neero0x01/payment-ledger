package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.FasterPaymentTest.PaymentView;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Scenario 6 end to end: the ledger sends Faster Payments through its HTTP adapter to the real FPS
 * simulator, built from its Dockerfile and run in a container with its own Postgres, and the
 * simulator's signed Scheme Outcomes come back to the ledger's callback endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureRestTestClient
@Import({
  TestcontainersConfiguration.class,
  LedgerFixture.class,
  Sessions.class,
  FasterPaymentEndToEndTest.RealPaymentScheme.class
})
class FasterPaymentEndToEndTest {

  private static final String HMAC_KEY = "end-to-end-scheme-callback-key";
  private static final int SIMULATOR_PORT = 8081;
  private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();

  /** The ledger listens on a free port the simulator's container can reach on the host. */
  private static final int LEDGER_PORT = freePort();

  private static final Network NETWORK = Network.newNetwork();

  private static final PostgreSQLContainer SIMULATOR_DB =
      new PostgreSQLContainer("postgres:18")
          .withDatabaseName("fpssim")
          .withNetwork(NETWORK)
          .withNetworkAliases("fpssim-db");

  private static final GenericContainer<?> SIMULATOR =
      new GenericContainer<>(
              new ImageFromDockerfile("payment-ledger/fps-simulator-e2e", false)
                  .withFileFromPath("mvnw", REPOSITORY.resolve("mvnw"))
                  .withFileFromPath("pom.xml", REPOSITORY.resolve("pom.xml"))
                  .withFileFromPath(".mvn", REPOSITORY.resolve(".mvn"))
                  .withFileFromPath("ledger/pom.xml", REPOSITORY.resolve("ledger/pom.xml"))
                  .withFileFromPath("projector/pom.xml", REPOSITORY.resolve("projector/pom.xml"))
                  .withFileFromPath(
                      "fps-simulator/pom.xml", REPOSITORY.resolve("fps-simulator/pom.xml"))
                  .withFileFromPath("fps-simulator/src", REPOSITORY.resolve("fps-simulator/src"))
                  .withFileFromPath(
                      "fps-simulator/Dockerfile", REPOSITORY.resolve("fps-simulator/Dockerfile"))
                  .withDockerfilePath("fps-simulator/Dockerfile"))
          .withNetwork(NETWORK)
          .withExposedPorts(SIMULATOR_PORT)
          .withEnv("FPSSIM_DB_URL", "jdbc:postgresql://fpssim-db:5432/fpssim")
          .withEnv("FPSSIM_DB_USER", SIMULATOR_DB.getUsername())
          .withEnv("FPSSIM_DB_PASSWORD", SIMULATOR_DB.getPassword())
          .withEnv("FPSSIM_LEDGER_BASE_URL", "http://host.testcontainers.internal:" + LEDGER_PORT)
          .withEnv("FPSSIM_CALLBACK_HMAC_KEY", HMAC_KEY)
          .withEnv("FPSSIM_ACCEPT_RATE", "1.0")
          .withEnv("FPSSIM_LATENCY_P50_MS", "100")
          .withEnv("FPSSIM_LATENCY_P99_MS", "500")
          .dependsOn(SIMULATOR_DB)
          .waitingFor(
              Wait.forHttp("/admin/settings")
                  .forPort(SIMULATOR_PORT)
                  .withStartupTimeout(Duration.ofMinutes(2)));

  static {
    Testcontainers.exposeHostPorts(LEDGER_PORT);
    SIMULATOR.start();
  }

  @DynamicPropertySource
  static void ledgerTalksToTheSimulator(DynamicPropertyRegistry registry) {
    registry.add("server.port", () -> LEDGER_PORT);
    registry.add(
        "ledger.scheme.base-url",
        () ->
            "http://%s:%d".formatted(SIMULATOR.getHost(), SIMULATOR.getMappedPort(SIMULATOR_PORT)));
    registry.add("ledger.scheme.callback-hmac-key", () -> HMAC_KEY);
  }

  /**
   * Component scanning picks up the in-memory {@link FakePaymentScheme} from the test sources, and
   * as {@code @Primary} it would win over the HTTP adapter. This test removes it, so Scheme
   * Submissions really go over HTTP to the simulator.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class RealPaymentScheme {

    @Bean
    static BeanDefinitionRegistryPostProcessor withoutTheFakePaymentScheme() {
      return registry -> {
        for (String name : registry.getBeanDefinitionNames()) {
          if (FakePaymentScheme.class
              .getName()
              .equals(registry.getBeanDefinition(name).getBeanClassName())) {
            registry.removeBeanDefinition(name);
          }
        }
      };
    }
  }

  @Autowired LedgerFixture ledger;
  @Autowired Sessions sessions;

  AccountId a;
  RestTestClient customer;

  @BeforeEach
  void givenAHas100Pounds() {
    a = ledger.customerAccountWithBalance(Money.ofPence(100_00));
    customer = sessions.customerOwning(a);
  }

  @Test
  void a_faster_payment_the_simulator_accepts_is_completed() {
    PaymentView pending = makeFasterPayment(FasterPaymentTest.randomAccountNumber(), 40_00);

    PaymentView completed = awaitSettled(pending.id());

    assertThat(completed.status()).isEqualTo("COMPLETED");
    assertThat(completed.transactionIds()).hasSize(1);
  }

  @Test
  void a_faster_payment_to_an_account_ending_0401_is_rejected_ac04_and_reversed() {
    PaymentView pending = makeFasterPayment("12340401", 40_00);

    PaymentView rejected = awaitSettled(pending.id());

    assertThat(rejected.status()).isEqualTo("REJECTED");
    assertThat(rejected.rejectionReason()).isEqualTo("SCHEME_REJECTED");
    assertThat(rejected.schemeReasonCode()).isEqualTo("AC04");
    // The Payment's Transaction and its Reversal.
    assertThat(rejected.transactionIds()).hasSize(2);
  }

  private PaymentView makeFasterPayment(String payeeAccountNumber, long amountPence) {
    PaymentView payment =
        customer
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(FasterPaymentTest.fasterPayment(a, payeeAccountNumber, amountPence))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CREATED)
            .expectBody(PaymentView.class)
            .returnResult()
            .getResponseBody();
    assertThat(payment).isNotNull();
    assertThat(payment.status()).isEqualTo("PENDING");
    return payment;
  }

  /** The Payment once the simulator's Scheme Outcome has settled it, waiting up to 30 s. */
  private PaymentView awaitSettled(UUID id) {
    return await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> payment(id), payment -> !payment.status().equals("PENDING"));
  }

  private PaymentView payment(UUID id) {
    PaymentView payment =
        customer
            .get()
            .uri("/v1/payments/{id}", id)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(PaymentView.class)
            .returnResult()
            .getResponseBody();
    assertThat(payment).isNotNull();
    return payment;
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
