package com.paymentledger.fpssimulator;

import java.time.Duration;
import java.util.random.RandomGenerator;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the simulated scheme behaves, so failure handling can be shown live: the share of decided
 * submissions it accepts (the rest it rejects), the share it black-holes (never sends an outcome
 * for) and the median and 99th-percentile delay before it sends an outcome. Starts from the
 * environment and can be changed at runtime through {@code PUT /admin/settings}.
 */
@ConfigurationProperties("fpssim")
record SimulatorSettings(
    double acceptRate, double blackHoleRate, long latencyP50Ms, long latencyP99Ms) {

  /** The standard normal distribution's 99th percentile. */
  private static final double Z_99 = 2.3263478740408408;

  SimulatorSettings {
    requireRate("acceptRate", acceptRate);
    requireRate("blackHoleRate", blackHoleRate);
    if (latencyP50Ms < 0 || latencyP99Ms < latencyP50Ms) {
      throw new IllegalArgumentException("Latency needs 0 <= latencyP50Ms <= latencyP99Ms");
    }
  }

  /**
   * A delay drawn from the log-normal distribution with this median and 99th percentile, the usual
   * shape of network latency: most outcomes come near the median, a few much later.
   */
  Duration sampleLatency(RandomGenerator random) {
    if (latencyP50Ms == 0) {
      return Duration.ZERO;
    }
    double sigma = Math.log((double) latencyP99Ms / latencyP50Ms) / Z_99;
    return Duration.ofMillis(Math.round(latencyP50Ms * Math.exp(sigma * random.nextGaussian())));
  }

  private static void requireRate(String name, double rate) {
    if (!(rate >= 0 && rate <= 1)) {
      throw new IllegalArgumentException(name + " must be between 0 and 1");
    }
  }
}
