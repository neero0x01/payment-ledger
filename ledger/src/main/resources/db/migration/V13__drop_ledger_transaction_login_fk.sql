-- ledger_transaction.principal_id names the Operator's login, which belongs to the accounts module.
-- Posting doesn't reach into another module's tables, foreign keys included (CODING_STANDARDS rule
-- 14, ADR 0002), so the column stays and its foreign key to login goes. The Operator id comes from
-- the authenticated Principal, never from the request body.

alter table ledger_transaction
    drop constraint ledger_transaction_principal_id_fkey;
