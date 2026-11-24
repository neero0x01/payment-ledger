package com.paymentledger.ledger.demo.internal;

import com.paymentledger.ledger.shared.Problem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 30 POSTs per minute per demo session (spec §8), by an in-memory token bucket kept in the session
 * itself, so it goes when the session does. Only demo sessions have a bucket: logins are not
 * public.
 */
@Configuration
class PostLimit implements WebMvcConfigurer, HandlerInterceptor {

  private static final String BUCKET = PostLimit.class.getName() + ".bucket";
  private static final int POSTS_PER_MINUTE = 30;

  private final Clock clock;

  PostLimit(Clock clock) {
    this.clock = clock;
  }

  /** Gives the demo session its own full bucket of POSTs. */
  void startFor(HttpSession session) {
    session.setAttribute(BUCKET, new TokenBucket(POSTS_PER_MINUTE, clock.instant()));
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this);
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!HttpMethod.POST.matches(request.getMethod())) {
      return true;
    }
    HttpSession session = request.getSession(false);
    if (session != null
        && session.getAttribute(BUCKET) instanceof TokenBucket bucket
        && !bucket.take(clock.instant())) {
      throw Problem.rateLimited(
          "A demo session may make " + POSTS_PER_MINUTE + " POSTs a minute; try again shortly");
    }
    return true;
  }

  /** Holds up to a minute's worth of tokens and refills steadily, one token at a time. */
  private static final class TokenBucket {

    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);

    private final int capacity;
    private double tokens;
    private Instant refilledAt;

    TokenBucket(int capacity, Instant now) {
      this.capacity = capacity;
      this.tokens = capacity;
      this.refilledAt = now;
    }

    synchronized boolean take(Instant now) {
      double elapsed = Duration.between(refilledAt, now).toNanos();
      tokens = Math.min(capacity, tokens + capacity * elapsed / REFILL_PERIOD.toNanos());
      refilledAt = now;
      if (tokens < 1) {
        return false;
      }
      tokens -= 1;
      return true;
    }
  }
}
