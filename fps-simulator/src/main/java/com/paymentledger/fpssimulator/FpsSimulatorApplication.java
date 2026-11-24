package com.paymentledger.fpssimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Stands in for the Faster Payments Scheme (ADR 0008): takes Scheme Submissions over HTTP and
 * answers each later with a signed Scheme Outcome sent to the ledger.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FpsSimulatorApplication {

  public static void main(String[] args) {
    SpringApplication.run(FpsSimulatorApplication.class, args);
  }
}
