package com.paymentledger.projector;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;

/**
 * Which events the projector traces (spec §13). An event the ledger published in a trace carries
 * its traceparent header, and applying it joins that trace. An event with none, such as each of the
 * million the seed loads, starts no trace of its own.
 */
@Configuration(proxyBeanMethods = false)
class ProjectorTracing {

  @Bean
  ObservationPredicate onlyEventsPublishedInATrace() {
    return (name, context) ->
        !(context instanceof KafkaRecordReceiverContext received)
            || received.getRecord().headers().lastHeader("traceparent") != null;
  }
}
