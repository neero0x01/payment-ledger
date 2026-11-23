package com.paymentledger.ledger.payments.internal;

import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Creates {@code payments.payment-status-changed.v1} on startup, kept 30 days (spec §5). */
@Configuration
class PaymentStatusChangedTopic {

  @Bean
  NewTopic paymentStatusChangedV1() {
    return TopicBuilder.name(PaymentStatusChangedEvent.TOPIC)
        .partitions(3)
        .replicas(1)
        .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(Duration.ofDays(30).toMillis()))
        .build();
  }
}
