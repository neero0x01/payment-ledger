package com.paymentledger.ledger.posting.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock partition maintenance tells the current month by. Booking Times never come from it:
 * they come from the database's clock (ADR 0004).
 */
@Configuration(proxyBeanMethods = false)
class PartitionMaintenanceClock {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
