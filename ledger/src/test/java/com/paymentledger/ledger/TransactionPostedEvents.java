package com.paymentledger.ledger;

import com.paymentledger.ledger.shared.TransactionId;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
    return TopicReader.publishedForAll(
        kafka, TOPIC, transactions.stream().map(id -> id.value().toString()).toList());
  }

  /** Every event on the topic now, without waiting for any in particular. */
  public List<ConsumerRecord<String, String>> publishedNow() {
    return TopicReader.readAll(kafka, TOPIC);
  }
}
