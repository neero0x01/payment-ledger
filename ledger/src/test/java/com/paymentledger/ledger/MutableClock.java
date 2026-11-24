package com.paymentledger.ledger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A clock a test moves forward by hand, for limits that last minutes or days. */
public final class MutableClock extends Clock {

  private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

  public void advance(Duration duration) {
    now.updateAndGet(instant -> instant.plus(duration));
  }

  @Override
  public Instant instant() {
    return now.get();
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("A MutableClock is always UTC");
  }

  /** Replaces the application's clock with a {@link MutableClock}. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class Configuration {

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock();
    }
  }
}
