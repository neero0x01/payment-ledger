package com.paymentledger.fpssimulator;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The simulator's own {@code submission} table: the decision on each End-to-End Id. */
@Repository
class SubmissionStore {

  private final JdbcClient db;

  SubmissionStore(JdbcClient db) {
    this.db = db;
  }

  /**
   * Stores the decision on the End-to-End Id unless one is already stored, and returns whichever is
   * stored now: a submission is decided once, however often it is sent.
   */
  Decision decideOnce(String endToEndId, Decision decision) {
    db.sql(
            """
            insert into submission (end_to_end_id, black_holed, outcome, reason_code)
            values (:endToEndId, :blackHoled, :outcome, :reasonCode)
            on conflict (end_to_end_id) do nothing
            """)
        .param("endToEndId", endToEndId)
        .param("blackHoled", decision.outcome().isEmpty())
        .param("outcome", decision.outcome().map(o -> o.status().name()).orElse(null))
        .param(
            "reasonCode",
            decision
                .outcome()
                .map(SchemeOutcome::reasonCode)
                .map(SchemeOutcome.ReasonCode::name)
                .orElse(null))
        .update();
    return db.sql(
            """
            select outcome, reason_code
            from submission
            where end_to_end_id = :endToEndId
            """)
        .param("endToEndId", endToEndId)
        .query((rs, row) -> decisionOf(rs.getString("outcome"), rs.getString("reason_code")))
        .single();
  }

  /** Records that the ledger took the Scheme Outcome for the End-to-End Id. */
  void callbackSent(String endToEndId) {
    db.sql(
            """
            update submission
            set callback_sent_at = now()
            where end_to_end_id = :endToEndId
            """)
        .param("endToEndId", endToEndId)
        .update();
  }

  private static Decision decisionOf(@Nullable String outcome, @Nullable String reasonCode) {
    if (outcome == null) {
      return Decision.BLACK_HOLE;
    }
    return new Decision(
        Optional.of(
            new SchemeOutcome(
                SchemeOutcome.Status.valueOf(outcome),
                reasonCode == null ? null : SchemeOutcome.ReasonCode.valueOf(reasonCode))));
  }
}
