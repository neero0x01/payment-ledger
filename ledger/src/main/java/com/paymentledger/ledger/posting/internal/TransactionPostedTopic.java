package com.paymentledger.ledger.posting.internal;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Creates {@code ledger.transaction-posted.v1} on startup, kept forever (spec §5). */
@Configuration
class TransactionPostedTopic {

  @Bean
  NewTopic transactionPostedV1() {
    return TopicBuilder.name(TransactionPostedEvent.TOPIC)
        .partitions(3)
        .replicas(1)
        .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
        .build();
  }
}
