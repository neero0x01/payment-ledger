package com.paymentledger.ledger;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real Postgres 18 and real Kafka for every integration test; never H2. Every test context in the
 * JVM shares the same two containers, started once, so a run holds one Postgres and one Kafka
 * however many contexts Spring caches. A test that stops a container, or needs topics and consumer
 * groups no other context touches, imports {@link DedicatedContainersConfiguration} or {@link
 * RestartableKafkaConfiguration} instead.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  static final String KAFKA_IMAGE = "apache/kafka:4.1.1";

  private static final PostgreSQLContainer POSTGRES = PostgresTestcontainer.create();
  private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

  static {
    Startables.deepStart(POSTGRES, KAFKA).join();
  }

  /*
   * Prototype beans, so no context stops the shared containers when it closes (a singleton
   * container bean is stopped with its context); Ryuk removes them when the JVM exits.
   */

  @Bean
  @Scope(BeanDefinition.SCOPE_PROTOTYPE)
  PostgreSQLContainer postgres() {
    return POSTGRES;
  }

  @Bean
  @Scope(BeanDefinition.SCOPE_PROTOTYPE)
  KafkaContainer kafka() {
    return KAFKA;
  }

  @Bean
  DynamicPropertyRegistrar sharedContainers() {
    return registry -> {
      registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
      registry.add("spring.datasource.username", POSTGRES::getUsername);
      registry.add("spring.datasource.password", POSTGRES::getPassword);
      PostgresTestcontainer.registerSchemaOwnerAndReadModel(registry, POSTGRES);
      registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    };
  }
}
