package com.paymentledger.fpssimulator;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** The settings the simulator runs with now: the environment's until an admin replaces them. */
@Component
class CurrentSettings {

  private final AtomicReference<SimulatorSettings> current;

  CurrentSettings(SimulatorSettings fromEnvironment) {
    this.current = new AtomicReference<>(fromEnvironment);
  }

  SimulatorSettings get() {
    return current.get();
  }

  void replace(SimulatorSettings settings) {
    current.set(settings);
  }
}
