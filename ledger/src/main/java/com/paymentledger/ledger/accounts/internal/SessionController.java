package com.paymentledger.ledger.accounts.internal;

import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.Principal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the web page who is logged in. Logging in is {@code POST /v1/sessions}. */
@RestController
class SessionController {

  @GetMapping(path = "/{version}/session", version = "1")
  SessionResponse session(@AuthenticationPrincipal Principal principal) {
    return new SessionResponse(
        principal.role().name(), principal.customerId().map(CustomerId::value).orElse(null));
  }

  record SessionResponse(String role, @Nullable UUID customerId) {}
}
