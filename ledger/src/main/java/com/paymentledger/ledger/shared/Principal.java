package com.paymentledger.ledger.shared;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Who a request is made by: a Customer, or an Operator, who is no Customer. */
public record Principal(Role role, @Nullable CustomerId customer) {

  public Principal {
    Objects.requireNonNull(role, "role");
    if ((role == Role.CUSTOMER) != (customer != null)) {
      throw new IllegalArgumentException("A Customer Principal, and only one, names a Customer");
    }
  }

  public static Principal customer(CustomerId customer) {
    return new Principal(Role.CUSTOMER, Objects.requireNonNull(customer, "customer"));
  }

  public static Principal operator() {
    return new Principal(Role.OPERATOR, null);
  }

  /** The Customer this Principal is, or empty for an Operator. */
  public Optional<CustomerId> customerId() {
    return Optional.ofNullable(customer);
  }
}
