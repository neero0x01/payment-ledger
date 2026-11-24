package com.paymentledger.ledger.demo.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The wall clock the demo limits are measured by. Never used for Booking Time (ADR 0004). */
@Configuration(proxyBeanMethods = false)
class DemoClock {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
