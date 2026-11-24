package com.paymentledger.ledger.payments.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/** The Stuck Payments gauge (spec §11): how many Faster Payments are Stuck Payments now. */
@Component
class StuckPaymentsGauge implements MeterBinder {

  private final PaymentRepository payments;

  StuckPaymentsGauge(PaymentRepository payments) {
    this.payments = payments;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("ledger.payments.stuck", payments, PaymentRepository::countStuckPayments)
        .description("Faster Payments still Pending after their Scheme Outcome deadline")
        .register(registry);
  }
}
