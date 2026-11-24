package com.paymentledger.seed;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;

/**
 * The seed's one source of randomness, started from a fixed seed, so every run draws the same
 * numbers in the same order. {@link Random}'s algorithm is fixed by its specification, so the draws
 * don't change between JDKs.
 */
final class SeedRandom {

  private final Random random;

  SeedRandom(long seed) {
    this.random = new Random(seed);
  }

  /** A uniform int from 0 (inclusive) to bound (exclusive). */
  int below(int bound) {
    return random.nextInt(bound);
  }

  /** A uniform long from min to max, both inclusive. */
  long between(long min, long max) {
    return min + (long) (random.nextDouble() * (max - min + 1));
  }

  /** True with the given probability. */
  boolean chance(double probability) {
    return random.nextDouble() < probability;
  }

  /** A draw from the exponential distribution with mean 1. */
  double exponential() {
    return -Math.log(1 - random.nextDouble());
  }

  /** A draw from the Poisson distribution with the given mean, by Knuth's method. */
  int poisson(double mean) {
    double limit = Math.exp(-mean);
    double product = random.nextDouble();
    int count = 0;
    while (product > limit) {
      count++;
      product *= random.nextDouble();
    }
    return count;
  }

  /**
   * A UUID version 7 for something made at the moment: its first 48 bits are the moment in Unix
   * milliseconds, as Postgres's uuidv7() makes them, and the rest are drawn from this source.
   */
  UUID uuidV7(Instant moment) {
    long millis = moment.toEpochMilli();
    long high = (millis << 16) | 0x7000L | (random.nextLong() & 0x0FFFL);
    long low = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
    return new UUID(high, low);
  }

  /** A random UUID version 4. */
  UUID uuidV4() {
    long high = (random.nextLong() & 0xFFFF_FFFF_FFFF_0FFFL) | 0x4000L;
    long low = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
    return new UUID(high, low);
  }

  /** A string of the given number of decimal digits. */
  String digits(int count) {
    StringBuilder digits = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      digits.append((char) ('0' + random.nextInt(10)));
    }
    return digits.toString();
  }
}
