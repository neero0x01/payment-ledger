package com.paymentledger.ledger.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

/** Property tests on Money: GBP pence that never mix with other currencies or overflow. */
class MoneyPropertiesTest {

  private static final Currency EUR = Currency.getInstance("EUR");

  @Test
  void adds_pence_to_pence() {
    assertThat(Money.ofPence(1_50).plus(Money.ofPence(2_25))).isEqualTo(Money.ofPence(3_75));
  }

  @Property
  void refuses_to_add_amounts_in_different_currencies(
      @ForAll @LongRange(min = 1, max = 1_000_000_000_000L) long gbpPence,
      @ForAll @LongRange(min = 1, max = 1_000_000_000_000L) long eurCents) {
    Money pounds = Money.ofPence(gbpPence);
    Money euros = Money.of(eurCents, EUR);

    assertThatThrownBy(() -> pounds.plus(euros)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> euros.plus(pounds)).isInstanceOf(IllegalArgumentException.class);
  }

  @Property
  void refuses_a_sum_that_overflows(@ForAll("overflowingPairs") long[] pair) {
    assertThatThrownBy(() -> Money.ofPence(pair[0]).plus(Money.ofPence(pair[1])))
        .isInstanceOf(ArithmeticException.class);
  }

  @Provide
  Arbitrary<long[]> overflowingPairs() {
    return Arbitraries.longs()
        .between(1, Long.MAX_VALUE)
        .flatMap(a ->
            Combinators.combine(
                    Arbitraries.just(a),
                    Arbitraries.longs().between(Long.MAX_VALUE - a + 1, Long.MAX_VALUE))
                .as((x, b) -> new long[] {x, b}));
  }
}
