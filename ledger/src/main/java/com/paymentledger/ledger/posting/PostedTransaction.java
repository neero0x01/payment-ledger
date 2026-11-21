package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.TransactionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** A Transaction as recorded in the ledger. It never changes once posted. */
public record PostedTransaction(
    TransactionId id,
    TransactionKind kind,
    Instant bookingTime,
    @Nullable PaymentId originatingPayment,
    List<Entry> entries) {

  public PostedTransaction {
    entries = List.copyOf(entries);
  }

  public Optional<PaymentId> payment() {
    return Optional.ofNullable(originatingPayment);
  }
}
