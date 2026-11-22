package com.paymentledger.ledger.outbox;

/**
 * The transactional outbox (ADR 0005): events appended here commit with the change they describe,
 * or not at all, and a publisher then sends them to Kafka at least once.
 */
public interface Outbox {

  /**
   * Appends the event in the caller's database transaction, giving it a new event id. Needs a
   * database transaction already in progress.
   */
  void append(OutboxEvent event);
}
