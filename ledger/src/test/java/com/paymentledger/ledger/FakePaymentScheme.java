package com.paymentledger.ledger;

import static org.awaitility.Awaitility.await;

import com.paymentledger.ledger.payments.PaymentScheme;
import com.paymentledger.ledger.payments.SchemeSubmission;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.Money;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * An in-memory Faster Payments Scheme behind the PaymentScheme port. It records every Scheme
 * Submission it receives; the test then plays the scheme's part by sending signed Scheme Outcomes
 * to the ledger's callback endpoint. {@code @Primary}, so it replaces the HTTP adapter in any test
 * that imports it.
 */
@Component
@Primary
public class FakePaymentScheme implements PaymentScheme {

  /** A Scheme Submission, and the debtor's committed Balance when it arrived. */
  public record Received(SchemeSubmission submission, Money debtorBalance) {}

  private final Posting posting;
  private final List<Received> received = new CopyOnWriteArrayList<>();

  FakePaymentScheme(Posting posting) {
    this.posting = posting;
  }

  @Override
  public void submit(SchemeSubmission submission) {
    // Read on another connection, so it sees only what the ledger has committed.
    Money balance = posting.balanceOf(submission.debtorAccount()).orElseThrow();
    received.add(new Received(submission, balance));
  }

  /** Every Scheme Submission received so far to the creditor account number. */
  public List<Received> submissionsTo(String creditorAccountNumber) {
    return received.stream()
        .filter(r -> r.submission().creditor().accountNumber().equals(creditorAccountNumber))
        .toList();
  }

  /** The first Scheme Submission to the creditor account number, waiting up to 10 s for it. */
  public Received awaitSubmissionTo(String creditorAccountNumber) {
    return await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> submissionsTo(creditorAccountNumber), list -> !list.isEmpty())
        .getFirst();
  }
}
