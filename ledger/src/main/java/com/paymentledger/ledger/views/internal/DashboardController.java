package com.paymentledger.ledger.views.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Operator dashboard: live system health. */
@RestController
class DashboardController {

  @GetMapping(path = "/{version}/dashboard", version = "1")
  DashboardResponse dashboard() {
    return new DashboardResponse();
  }

  record DashboardResponse() {}
}
