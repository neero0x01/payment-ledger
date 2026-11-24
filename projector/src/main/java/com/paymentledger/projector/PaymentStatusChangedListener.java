package com.paymentledger.projector;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Consumes {@code payments.payment-status-changed.v1} into the dashboard's figures. */
@Component
class PaymentStatusChangedListener {

  static final String TOPIC = "payments.payment-status-changed.v1";

  private final JsonMapper json;
  private final ReadModel readModel;

  PaymentStatusChangedListener(JsonMapper json, ReadModel readModel) {
    this.json = json;
    this.readModel = readModel;
  }

  /** Not while the projector is run with --rebuild, which replays the topic itself. */
  @KafkaListener(
      topics = TOPIC,
      autoStartup = "#{!environment.containsProperty('" + Rebuild.OPTION + "')}")
  void on(String event) {
    readModel.apply(json.readValue(event, PaymentStatusChanged.class));
  }
}
