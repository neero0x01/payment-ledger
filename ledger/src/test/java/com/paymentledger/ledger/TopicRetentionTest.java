package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The event contracts' retention (spec §5): the Read Model is rebuilt by replaying
 * ledger.transaction-posted.v1 from offset 0, so it keeps its events forever, while
 * payments.payment-status-changed.v1 keeps them for 30 days.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TopicRetentionTest {

  @Autowired KafkaContainer kafka;

  @Test
  void transaction_posted_keeps_its_events_forever() {
    assertThat(retentionMs(TransactionPostedEvents.TOPIC)).isEqualTo("-1");
  }

  @Test
  void payment_status_changed_keeps_its_events_for_30_days() {
    assertThat(retentionMs(PaymentStatusChangedEvents.TOPIC))
        .isEqualTo(Long.toString(Duration.ofDays(30).toMillis()));
  }

  private String retentionMs(String topic) {
    ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
    try (Admin admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
      Config config = admin.describeConfigs(List.of(resource)).all().get().get(resource);
      return config.get(TopicConfig.RETENTION_MS_CONFIG).value();
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
