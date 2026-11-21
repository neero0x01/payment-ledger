package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.PaymentId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** A Transaction to be posted. */
public record NewTransaction(
    TransactionKind kind, @Nullable PaymentId originatingPayment, List<Entry> entries) {

  public NewTransaction {
    Objects.requireNonNull(kind, "kind");
    entries = List.copyOf(entries);
    if (entries.size() < 2) {
      throw new IllegalArgumentException("A Transaction needs at least two Entries");
    }
  }

  public static NewTransaction funding(List<Entry> entries) {
    return new NewTransaction(TransactionKind.FUNDING, null, entries);
  }

  public static NewTransaction forPayment(PaymentId payment, List<Entry> entries) {
    return new NewTransaction(TransactionKind.PAYMENT, Objects.requireNonNull(payment), entries);
  }

  public Optional<PaymentId> payment() {
    return Optional.ofNullable(originatingPayment);
  }
}
