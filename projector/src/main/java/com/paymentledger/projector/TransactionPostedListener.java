package com.paymentledger.projector;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Consumes {@code ledger.transaction-posted.v1} into the Read Model. */
@Component
class TransactionPostedListener {

  static final String TOPIC = "ledger.transaction-posted.v1";

  private final JsonMapper json;
  private final ReadModel readModel;

  TransactionPostedListener(JsonMapper json, ReadModel readModel) {
    this.json = json;
    this.readModel = readModel;
  }

  /** Not while the projector is run with --rebuild, which replays the topic itself. */
  @KafkaListener(
      topics = TOPIC,
      autoStartup = "#{!environment.containsProperty('" + Rebuild.OPTION + "')}")
  void on(String event) {
    readModel.apply(json.readValue(event, TransactionPosted.class));
  }
}
