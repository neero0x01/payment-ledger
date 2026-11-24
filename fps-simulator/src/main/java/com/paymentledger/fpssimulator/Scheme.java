package com.paymentledger.fpssimulator;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * The simulated Faster Payments Scheme. It decides each Scheme Submission once, stores the decision
 * on its End-to-End Id, and later sends the Scheme Outcome to the ledger. A re-sent submission gets
 * the stored outcome again, never a new decision.
 */
@Service
class Scheme implements DisposableBean {

  /** A Payee account number with this ending is always rejected as closed, for tests and demos. */
  static final String CLOSED_ACCOUNT_SUFFIX = "0401";

  /** The reason codes of random Scheme Rejections; AC04 is kept for the closed accounts. */
  private static final List<SchemeOutcome.ReasonCode> RANDOM_REJECTIONS =
      List.of(
          SchemeOutcome.ReasonCode.AC01,
          SchemeOutcome.ReasonCode.AG01,
          SchemeOutcome.ReasonCode.MS03);

  private static final Logger log = LoggerFactory.getLogger(Scheme.class);

  private final SubmissionStore submissions;
  private final LedgerCallbacks ledger;
  private final CurrentSettings settings;
  private final ScheduledExecutorService outcomes = Executors.newScheduledThreadPool(4);

  Scheme(SubmissionStore submissions, LedgerCallbacks ledger, CurrentSettings settings) {
    this.submissions = submissions;
    this.ledger = ledger;
    this.settings = settings;
  }

  /** Takes the Scheme Submission; its Scheme Outcome, if any, goes to the ledger later. */
  void receive(Pacs008Lite submission) {
    Decision decision = submissions.decideOnce(submission.endToEndId(), decide(submission));
    decision
        .outcome()
        .ifPresent(outcome ->
            outcomes.schedule(
                () -> sendOutcome(submission.endToEndId(), outcome), 0, TimeUnit.MILLISECONDS));
  }

  private Decision decide(Pacs008Lite submission) {
    if (submission.creditorAccountNumber().endsWith(CLOSED_ACCOUNT_SUFFIX)) {
      return Decision.send(SchemeOutcome.rejected(SchemeOutcome.ReasonCode.AC04));
    }
    ThreadLocalRandom random = ThreadLocalRandom.current();
    if (random.nextDouble() < settings.get().acceptRate()) {
      return Decision.send(SchemeOutcome.ACCEPTED);
    }
    return Decision.send(
        SchemeOutcome.rejected(RANDOM_REJECTIONS.get(random.nextInt(RANDOM_REJECTIONS.size()))));
  }

  private void sendOutcome(String endToEndId, SchemeOutcome outcome) {
    try {
      ledger.send(endToEndId, outcome);
      submissions.callbackSent(endToEndId);
    } catch (RuntimeException e) {
      // The ledger re-sends the submission when no outcome arrives, which sends it again.
      log.warn("Couldn't send the Scheme Outcome for {} to the ledger", endToEndId, e);
    }
  }

  @Override
  public void destroy() {
    outcomes.shutdownNow();
  }
}
