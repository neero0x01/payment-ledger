-- Tables added by later migrations are append-only for ledger_app by default: SELECT and INSERT,
-- never UPDATE (CODING_STANDARDS rule 8). A later table that must change, such as an outbox, grants
-- UPDATE in its own migration. This replaces the default V3 set, and V3's note on revoking UPDATE.

alter default privileges in schema public revoke update on tables from ledger_app;

-- The tables the application updates: a Payment moves from Pending to Completed or Rejected, and
-- the cached Balance changes with every posting. Already granted in V3, and stated here so the
-- list of mutable tables lives in one place.
grant update on payment, account_balance to ledger_app;
