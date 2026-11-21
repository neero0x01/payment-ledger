package com.paymentledger.ledger.shared.internal;

import com.paymentledger.ledger.shared.Problem;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Sessions and CSRF for the ledger API (spec §8). Logging in posts a username and password form to
 * {@code /v1/sessions}, which starts an in-memory server-side session. The CSRF token travels from
 * the {@code XSRF-TOKEN} cookie to the {@code X-XSRF-TOKEN} header. Refusals are RFC 9457 problems.
 */
@Configuration
class WebSecurity {

  @Bean
  SecurityFilterChain ledgerSecurity(
      HttpSecurity http, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver problems)
      throws Exception {
    http.authorizeHttpRequests(requests ->
            requests
                .dispatcherTypeMatchers(DispatcherType.ERROR)
                .permitAll()
                .anyRequest()
                .authenticated())
        .formLogin(login ->
            login
                .loginPage("/v1/sessions")
                .loginProcessingUrl("/v1/sessions")
                .successHandler((request, response, authentication) -> {
                  // Logging in replaces the CSRF token; send the new one's cookie now.
                  loadCsrfToken(request);
                  response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                })
                .failureHandler((request, response, exception) ->
                    problems.resolveException(
                        request,
                        response,
                        null,
                        Problem.unauthenticated("Wrong username or password")))
                .permitAll())
        .csrf(csrf -> csrf.spa())
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        .exceptionHandling(exceptions ->
            exceptions
                .authenticationEntryPoint((request, response, exception) ->
                    problems.resolveException(
                        request, response, null, Problem.unauthenticated("Log in first")))
                .accessDeniedHandler((request, response, exception) ->
                    problems.resolveException(
                        request, response, null, Problem.forbidden(exception.getMessage()))))
        .requestCache(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable);
    return http.build();
  }

  private static void loadCsrfToken(HttpServletRequest request) {
    if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
      token.getToken();
    }
  }

  /**
   * Loads the CSRF token on every request, so the first response a page gets sets the cookie it
   * reads the token from.
   */
  private static final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      loadCsrfToken(request);
      chain.doFilter(request, response);
    }
  }
}
