package com.paymentledger.ledger;

import com.paymentledger.projector.ProjectorApplication;
import java.util.stream.Stream;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the projector next to the ledger under test, in the same JVM, against the same Postgres (its
 * readmodel database) and Kafka. Import it beside the configuration that provides those containers.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ProjectorConfiguration {

  /** The bean name of the running projector. */
  static final String PROJECTOR = "projector";

  @Bean(name = PROJECTOR, destroyMethod = "close")
  ConfigurableApplicationContext projector(PostgreSQLContainer postgres, KafkaContainer kafka) {
    return run(postgres, kafka);
  }

  /**
   * Runs the projector against the containers with the extra command-line arguments, as an Operator
   * runs it; it returns once the projector has started, or finished a one-off command.
   */
  static ConfigurableApplicationContext run(
      PostgreSQLContainer postgres, KafkaContainer kafka, String... args) {
    String[] connections = {
      "--spring.datasource.url=" + PostgresTestcontainer.readModelUrl(postgres),
      "--spring.datasource.username=" + postgres.getUsername(),
      "--spring.datasource.password=" + postgres.getPassword(),
      "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers()
    };
    return ProjectorApplication.application()
        .run(Stream.concat(Stream.of(args), Stream.of(connections)).toArray(String[]::new));
  }
}
