package com.paymentledger.ledger.demo.internal;

import com.paymentledger.ledger.accounts.Logins;
import com.paymentledger.ledger.demo.internal.DemoSandboxes.DemoSandbox;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.Principal;
import com.paymentledger.ledger.shared.Problem;
import com.paymentledger.ledger.shared.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/demo-sessions} is public: it starts a session, without a username or password, as
 * the Customer of a new Demo Sandbox or as the demo Operator.
 */
@RestController
class DemoSessionController {

  private final SecurityContextRepository securityContexts =
      new HttpSessionSecurityContextRepository();
  private final DemoSandboxes sandboxes;
  private final Logins logins;
  private final SandboxLimits sandboxLimits;
  private final PostLimit postLimit;

  DemoSessionController(
      DemoSandboxes sandboxes, Logins logins, SandboxLimits sandboxLimits, PostLimit postLimit) {
    this.sandboxes = sandboxes;
    this.logins = logins;
    this.sandboxLimits = sandboxLimits;
    this.postLimit = postLimit;
  }

  @PostMapping(path = "/{version}/demo-sessions", version = "1")
  @ResponseStatus(HttpStatus.CREATED)
  DemoSessionResponse start(
      @RequestBody DemoSessionRequest request,
      HttpServletRequest http,
      HttpServletResponse response) {
    Role role = request.role();
    if (role == null) {
      throw Problem.validationFailed("A demo session's role is CUSTOMER or OPERATOR");
    }
    if (role == Role.OPERATOR) {
      logIn(Principal.operator(logins.demoOperator()), http, response);
      return new DemoSessionResponse(Role.OPERATOR.name(), null, List.of());
    }
    // The visitor's own address: the proxy in front of the ledger passes it in X-Forwarded-For.
    sandboxLimits.admit(http.getRemoteAddr());
    DemoSandbox sandbox = sandboxes.create();
    logIn(Principal.customer(sandbox.customer()), http, response);
    return DemoSessionResponse.customer(
        sandbox.customer(), sandbox.accounts().stream().map(AccountId::value).toList());
  }

  /** Starts a new server-side session for the Principal, as logging in does. */
  private void logIn(Principal principal, HttpServletRequest http, HttpServletResponse response) {
    if (http.getSession(false) != null) {
      http.changeSessionId();
    }
    Authentication authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            principal,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    securityContexts.saveContext(context, http, response);
    postLimit.startFor(http.getSession());
  }

  record DemoSessionRequest(@Nullable Role role) {}

  record DemoSessionResponse(String role, @Nullable UUID customerId, List<UUID> accountIds) {

    static DemoSessionResponse customer(CustomerId customer, List<UUID> accounts) {
      return new DemoSessionResponse(Role.CUSTOMER.name(), customer.value(), accounts);
    }
  }
}
