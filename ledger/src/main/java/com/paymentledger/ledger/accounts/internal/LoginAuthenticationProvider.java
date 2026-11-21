package com.paymentledger.ledger.accounts.internal;

import com.paymentledger.ledger.accounts.internal.JdbcLogins.StoredLogin;
import com.paymentledger.ledger.shared.Principal;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Checks a username and password against the stored logins. The session then holds the plain {@link
 * Principal}, with its Role as the only authority.
 */
@Component
class LoginAuthenticationProvider implements AuthenticationProvider {

  /** Checked against for an unknown username too, so both take as long as a wrong password. */
  private static final String NO_SUCH_LOGIN = JdbcLogins.PASSWORDS.encode("no such login");

  private final JdbcLogins logins;

  LoginAuthenticationProvider(JdbcLogins logins) {
    this.logins = logins;
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    Object credentials = authentication.getCredentials();
    String password = credentials == null ? "" : credentials.toString();
    Optional<StoredLogin> login = logins.find(authentication.getName());
    String hash = login.map(StoredLogin::passwordHash).orElse(NO_SUCH_LOGIN);
    if (!JdbcLogins.PASSWORDS.matches(password, hash) || login.isEmpty()) {
      throw new BadCredentialsException("Wrong username or password");
    }
    Principal principal = login.get().principal();
    return UsernamePasswordAuthenticationToken.authenticated(
        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
  }
}
