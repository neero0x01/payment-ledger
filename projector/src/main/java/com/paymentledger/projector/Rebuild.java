package com.paymentledger.projector;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code projector --rebuild}: rebuilds the Read Model by replaying Kafka. It migrates fresh tables
 * into the {@value #SCHEMA} schema, applies every event on the topics from offset 0 up to their
 * ends when it started, the same way the live projector does, and then swaps the fresh tables in
 * for the live ones in one database transaction. The views keep serving the old Read Model until
 * the swap.
 */
@Component
class Rebuild implements ApplicationRunner {

  /** The command-line option, given as {@code --rebuild}. */
  static final String OPTION = "rebuild";

  private static final String SCHEMA = "rebuild";

  private final Flyway flyway;
  private final JdbcClient jdbc;
  private final TransactionTemplate transaction;
  private final ReadModel readModel;
  private final JsonMapper json;
  private final ConsumerFactory<?, ?> consumers;

  Rebuild(
      Flyway flyway,
      JdbcClient jdbc,
      TransactionTemplate transaction,
      ReadModel readModel,
      JsonMapper json,
      ConsumerFactory<?, ?> consumers) {
    this.flyway = flyway;
    this.jdbc = jdbc;
    this.transaction = transaction;
    this.readModel = readModel;
    this.json = json;
    this.consumers = consumers;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!args.containsOption(OPTION)) {
      return;
    }
    createFreshTables();
    replay(TransactionPostedListener.TOPIC);
    swapIn();
  }

  /** The Read Model's tables, empty, from the same migrations as the live ones. */
  private void createFreshTables() {
    jdbc.sql("drop schema if exists rebuild cascade").update();
    Flyway.configure()
        .configuration(flyway.getConfiguration())
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .load()
        .migrate();
  }

  /**
   * Applies every event on the topic, from offset 0 to the end each partition had when the replay
   * started, to the fresh tables, one polled batch per database transaction.
   */
  private void replay(String topic) {
    try (KafkaConsumer<String, String> consumer = replayConsumer()) {
      List<TopicPartition> partitions =
          consumer.partitionsFor(topic).stream()
              .map(info -> new TopicPartition(info.topic(), info.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
      while (partitions.stream().anyMatch(p -> consumer.position(p) < ends.get(p))) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        transaction.executeWithoutResult(status -> {
          jdbc.sql("set local search_path to rebuild").update();
          records.forEach(
              record -> readModel.apply(json.readValue(record.value(), TransactionPosted.class)));
        });
      }
    }
  }

  /**
   * A consumer of the projector's Kafka settings outside its consumer group, so the replay never
   * moves the live projector's committed offsets.
   */
  private KafkaConsumer<String, String> replayConsumer() {
    Map<String, Object> config = new HashMap<>(consumers.getConfigurationProperties());
    config.remove(ConsumerConfig.GROUP_ID_CONFIG);
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    return new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer());
  }

  /**
   * Replaces each live table with its fresh one in one database transaction, so the views see
   * either the old Read Model or the rebuilt one, and lets the views' role read the fresh tables.
   */
  private void swapIn() {
    transaction.executeWithoutResult(status -> {
      jdbc.sql(
              """
              do $$
              declare
                  fresh text;
              begin
                  for fresh in
                      select tablename from pg_tables
                      where schemaname = 'rebuild' and tablename <> 'flyway_schema_history'
                  loop
                      execute format('drop table if exists public.%I', fresh);
                      execute format('alter table rebuild.%I set schema public', fresh);
                  end loop;
              end;
              $$
              """)
          .update();
      jdbc.sql("grant select on all tables in schema public to readmodel_reader").update();
      jdbc.sql("drop schema rebuild cascade").update();
    });
  }
}
