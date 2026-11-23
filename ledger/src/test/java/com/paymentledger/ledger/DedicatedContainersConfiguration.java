package com.paymentledger.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A Postgres and a Kafka of the context's own, closed with it, for a test whose outcome depends on
 * everything on a topic or in a consumer group, which the shared containers of {@link
 * TestcontainersConfiguration} hold for every other context too. Import it instead of that class.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(PostgresTestcontainer.class)
public class DedicatedContainersConfiguration {

  @Bean
  @ServiceConnection
  KafkaContainer kafka() {
    return new KafkaContainer(TestcontainersConfiguration.KAFKA_IMAGE);
  }
}
