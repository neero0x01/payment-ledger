package com.paymentledger.ledger;

import com.paymentledger.ledger.shared.PaymentId;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Reads {@code payments.payment-status-changed.v1} from the start, the way a consumer of the event
 * contract does.
 */
@Component
public class PaymentStatusChangedEvents {

  public static final String TOPIC = "payments.payment-status-changed.v1";

  private final KafkaContainer kafka;

  PaymentStatusChangedEvents(KafkaContainer kafka) {
    this.kafka = kafka;
  }

  /** Every event on the topic keyed by the Payment's id, waiting up to 30 s for the first. */
  public List<ConsumerRecord<String, String>> publishedFor(PaymentId payment) {
    String key = payment.value().toString();
    return TopicReader.publishedForAll(kafka, TOPIC, List.of(key)).stream()
        .filter(event -> key.equals(event.key()))
        .toList();
  }
}
