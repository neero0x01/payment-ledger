package com.paymentledger.ledger;

import com.paymentledger.projector.ProjectorApplication;
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

  @Bean(destroyMethod = "close")
  ConfigurableApplicationContext projector(PostgreSQLContainer postgres, KafkaContainer kafka) {
    return ProjectorApplication.application()
        .run(
            "--spring.datasource.url=" + PostgresTestcontainer.readModelUrl(postgres),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers());
  }
}
