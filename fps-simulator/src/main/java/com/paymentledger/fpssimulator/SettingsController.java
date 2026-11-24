package com.paymentledger.fpssimulator;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The admin endpoint: reads and replaces the simulator's settings while it runs. */
@RestController
class SettingsController {

  private final CurrentSettings settings;

  SettingsController(CurrentSettings settings) {
    this.settings = settings;
  }

  @GetMapping("/admin/settings")
  SimulatorSettings settings() {
    return settings.get();
  }

  @PutMapping("/admin/settings")
  SimulatorSettings replace(@RequestBody SimulatorSettings replacement) {
    settings.replace(replacement);
    return replacement;
  }
}
