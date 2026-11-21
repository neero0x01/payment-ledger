package com.paymentledger.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

  @Test
  void module_boundaries_are_respected() {
    ApplicationModules.of(LedgerApplication.class).verify();
  }
}
