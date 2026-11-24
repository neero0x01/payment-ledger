package com.paymentledger.fpssimulator;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

  private static final Logger log = LoggerFactory.getLogger(Scheme.class);

  private final SubmissionStore submissions;
  private final LedgerCallbacks ledger;
  private final ScheduledExecutorService outcomes = Executors.newScheduledThreadPool(4);

  Scheme(SubmissionStore submissions, LedgerCallbacks ledger) {
    this.submissions = submissions;
    this.ledger = ledger;
  }

  /** Takes the Scheme Submission; its Scheme Outcome, if any, goes to the ledger later. */
  void receive(Pacs008Lite submission) {
    Decision decision =
        submissions.decideOnce(submission.endToEndId(), Decision.send(SchemeOutcome.ACCEPTED));
    decision
        .outcome()
        .ifPresent(outcome ->
            outcomes.schedule(
                () -> sendOutcome(submission.endToEndId(), outcome), 0, TimeUnit.MILLISECONDS));
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
