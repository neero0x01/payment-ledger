-- When the ledger took a submission's Scheme Outcome, in the glossary's words: the ledger's
-- endpoint is still called /v1/scheme/callbacks, but what the simulator sends is a Scheme Outcome.
alter table submission rename column callback_sent_at to outcome_sent_at;
