package com.paymentledger.ledger.outbox.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs the polling publisher. */
@Configuration
@EnableScheduling
class OutboxScheduling {}
