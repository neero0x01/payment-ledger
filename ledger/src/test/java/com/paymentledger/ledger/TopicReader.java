package com.paymentledger.ledger;

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
import org.testcontainers.kafka.KafkaContainer;

/**
 * Reads a topic from the start with a fresh consumer, the way a consumer of an event contract does.
 */
final class TopicReader {

  private TopicReader() {}

  /**
   * Every event on the topic once it holds events for all the keys, waiting up to 30 s; or what is
   * there when the wait runs out.
   */
  static List<ConsumerRecord<String, String>> publishedForAll(
      KafkaContainer kafka, String topic, List<String> keys) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    List<ConsumerRecord<String, String>> events;
    do {
      events = readAll(kafka, topic);
      List<String> published = events.stream().map(ConsumerRecord::key).toList();
      if (published.containsAll(keys)) {
        return events;
      }
    } while (System.nanoTime() < deadline);
    return events;
  }

  /** Every event on the topic now, or none if it doesn't exist yet. */
  static List<ConsumerRecord<String, String>> readAll(KafkaContainer kafka, String topic) {
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
          consumer.listTopics(Duration.ofSeconds(5)).getOrDefault(topic, List.of()).stream()
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
