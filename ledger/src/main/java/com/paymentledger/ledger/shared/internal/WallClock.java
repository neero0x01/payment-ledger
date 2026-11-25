package com.paymentledger.ledger.shared.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's wall clock, in UTC, for any module that measures time: the demo limits and the
 * dashboard's windows. Tests swap it for a clock they move. Never used for Booking Time, which the
 * database reads after the balance locks (ADR 0004).
 */
@Configuration(proxyBeanMethods = false)
class WallClock {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
