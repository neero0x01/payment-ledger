package com.paymentledger.projector;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * How the projector's listener handles an event it fails to apply. The Read Model must never miss
 * an event, so a failure such as the readmodel database being down is retried, with a backoff
 * growing to 30 s, until it succeeds; the partition waits meanwhile. Only an event that can't be
 * parsed is logged and skipped, as retrying it can never succeed.
 */
@Configuration(proxyBeanMethods = false)
class ProjectorErrorHandling {

  private static final Duration FIRST_RETRY = Duration.ofMillis(500);
  private static final Duration LONGEST_RETRY = Duration.ofSeconds(30);

  @Bean
  CommonErrorHandler retryUntilApplied() {
    ExponentialBackOff backOff = new ExponentialBackOff(FIRST_RETRY.toMillis(), 2);
    backOff.setMaxInterval(LONGEST_RETRY.toMillis());
    DefaultErrorHandler handler = new DefaultErrorHandler(backOff);
    handler.addNotRetryableExceptions(JacksonException.class);
    return handler;
  }
}
