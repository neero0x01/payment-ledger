package com.paymentledger.ledger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A clock a test can move to another moment, for the ledger's jobs that run by the calendar. It
 * starts at the real time. The database's own clock, which Booking Times come from, never moves.
 */
public final class MovableClock extends Clock {

  private volatile Instant now = Instant.now();

  /** Moves the clock to the moment given, where it stays. */
  public void moveTo(Instant moment) {
    now = moment;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("A MovableClock is always in UTC");
  }

  @Override
  public Instant instant() {
    return now;
  }

  /** Puts a MovableClock in the context in place of the system clock. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class Configuration {

    @Bean
    @Primary
    MovableClock movableClock() {
      return new MovableClock();
    }
  }
}
