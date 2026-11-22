package com.paymentledger.ledger;

import com.paymentledger.ledger.shared.TransactionId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.stereotype.Component;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Reads {@code ledger.transaction-posted.v1} from the start, the way a consumer of the event
 * contract does.
 */
@Component
public class TransactionPostedEvents {

  public static final String TOPIC = "ledger.transaction-posted.v1";

  private final KafkaContainer kafka;

  TransactionPostedEvents(KafkaContainer kafka) {
    this.kafka = kafka;
  }

  /** Every event on the topic keyed by the Transaction's id, waiting up to 30 s for the first. */
  public List<ConsumerRecord<String, String>> publishedFor(TransactionId transaction) {
    return publishedForAll(List.of(transaction)).stream()
        .filter(event -> transaction.value().toString().equals(event.key()))
        .toList();
  }

  /** Every event on the topic once it holds events for all the Transactions, waiting up to 30 s. */
  public List<ConsumerRecord<String, String>> publishedForAll(List<TransactionId> transactions) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    List<String> keys = transactions.stream().map(id -> id.value().toString()).toList();
    List<ConsumerRecord<String, String>> events;
    do {
      events = readAll();
      List<String> published = events.stream().map(ConsumerRecord::key).toList();
      if (published.containsAll(keys)) {
        return events;
      }
    } while (System.nanoTime() < deadline);
    return events;
  }

  /** Every event on the topic now, or none if it doesn't exist yet. */
  private List<ConsumerRecord<String, String>> readAll() {
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "test-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false),
            new StringDeserializer(),
            new StringDeserializer())) {
      List<TopicPartition> partitions =
          consumer.listTopics(Duration.ofSeconds(5)).getOrDefault(TOPIC, List.of()).stream()
              .map(info -> new TopicPartition(info.topic(), info.partition()))
              .toList();
      if (partitions.isEmpty()) {
        sleep();
        return List.of();
      }
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
      List<ConsumerRecord<String, String>> events = new ArrayList<>();
      while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
        consumer.poll(Duration.ofMillis(200)).forEach(events::add);
      }
      if (events.isEmpty()) {
        sleep();
      }
      return events;
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(200);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
