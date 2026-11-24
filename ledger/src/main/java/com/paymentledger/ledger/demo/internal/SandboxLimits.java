package com.paymentledger.ledger.demo.internal;

import com.paymentledger.ledger.shared.Problem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How many Demo Sandboxes may be created (spec §8): one per IP address per 10 minutes, and so many
 * a day across every visitor, both over a rolling window. Kept in memory, so a redeploy resets
 * them.
 */
@Component
class SandboxLimits {

  private static final Duration PER_IP = Duration.ofMinutes(10);
  private static final Duration PER_DAY = Duration.ofDays(1);

  private final Clock clock;
  private final int sandboxesPerDay;
  private final Map<String, Instant> lastSandboxByIp = new HashMap<>();
  private final Deque<Instant> sandboxesToday = new ArrayDeque<>();

  SandboxLimits(Clock clock, @Value("${ledger.demo.sandboxes-per-day}") int sandboxesPerDay) {
    this.clock = clock;
    this.sandboxesPerDay = sandboxesPerDay;
  }

  /**
   * Counts a new sandbox for the IP address, or refuses it as rate-limited when that would go
   * beyond a limit.
   */
  synchronized void admit(String ip) {
    Instant now = clock.instant();
    lastSandboxByIp.values().removeIf(created -> !created.plus(PER_IP).isAfter(now));
    while (!sandboxesToday.isEmpty() && !sandboxesToday.peekFirst().plus(PER_DAY).isAfter(now)) {
      sandboxesToday.removeFirst();
    }
    if (lastSandboxByIp.containsKey(ip)) {
      throw Problem.rateLimited("One Demo Sandbox per visitor every 10 minutes; try again later");
    }
    if (sandboxesToday.size() >= sandboxesPerDay) {
      throw Problem.rateLimited("The demo has made all its Demo Sandboxes for today");
    }
    lastSandboxByIp.put(ip, now);
    sandboxesToday.addLast(now);
  }
}
