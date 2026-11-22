package com.paymentledger.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

/** Real Postgres 18 and real Kafka for every integration test; never H2. */
@TestConfiguration(proxyBeanMethods = false)
@Import(PostgresTestcontainer.class)
public class TestcontainersConfiguration {

  static final String KAFKA_IMAGE = "apache/kafka:4.1.1";

  @Bean
  @ServiceConnection
  KafkaContainer kafka() {
    return new KafkaContainer(KAFKA_IMAGE);
  }
}
