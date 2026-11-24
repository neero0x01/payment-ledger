package com.paymentledger.fpssimulator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the simulated scheme behaves, so failure handling can be shown live: the share of decided
 * submissions it accepts (the rest it rejects), the share it black-holes (never sends an outcome
 * for) and the median and 99th-percentile delay before it sends an outcome. Starts from the
 * environment and can be changed at runtime through {@code PUT /admin/settings}.
 */
@ConfigurationProperties("fpssim")
record SimulatorSettings(
    double acceptRate, double blackHoleRate, long latencyP50Ms, long latencyP99Ms) {}
