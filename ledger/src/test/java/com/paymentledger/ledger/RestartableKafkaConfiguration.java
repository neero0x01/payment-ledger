package com.paymentledger.ledger;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Real Postgres and a Kafka container that a test can stop and start again. Docker gives a
 * restarted container a new random host port, but Kafka advertises the port it started with, so
 * this one is bound to a fixed free port. A context of its own, so stopping Kafka never touches
 * other tests.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(PostgresTestcontainer.class)
public class RestartableKafkaConfiguration {

  private static final int KAFKA_PORT = 9092;

  @Bean
  @ServiceConnection
  KafkaContainer kafka() {
    int hostPort = freePort();
    return new KafkaContainer(TestcontainersConfiguration.KAFKA_IMAGE)
        .withCreateContainerCmdModifier(create ->
            create
                .getHostConfig()
                .withPortBindings(
                    new PortBinding(
                        Ports.Binding.bindPort(hostPort), new ExposedPort(KAFKA_PORT))));
  }

  /** Stops the Kafka container without removing it, as an outage would. */
  public static void stop(KafkaContainer kafka) {
    kafka.getDockerClient().stopContainerCmd(kafka.getContainerId()).exec();
  }

  /** Starts the stopped Kafka container again on the same port. */
  public static void start(KafkaContainer kafka) {
    kafka.getDockerClient().startContainerCmd(kafka.getContainerId()).exec();
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
