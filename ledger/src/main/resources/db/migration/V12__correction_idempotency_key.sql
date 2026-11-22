-- A Correction carries the Idempotency-Key it was posted with and a SHA-256 hash of its canonical
-- request, unique per principal, so a retry replays the stored Correction instead of posting a
-- second one (ADR 0007, spec §6). The principal is the Operator who posted it, by login id.
-- Corrections posted before keys existed have none, so they can never be replayed; no other kind of
-- Transaction ever has one. Existing rows are left as they are (CODING_STANDARDS rule 8).

alter table ledger_transaction
    add column principal_id    uuid references login (id),
    add column idempotency_key text,
    add column request_hash    text,
    add constraint ledger_transaction_idempotency_check
        check ((principal_id is null and idempotency_key is null and request_hash is null)
            or (kind = 'CORRECTION'
                and principal_id is not null
                and idempotency_key is not null
                and request_hash is not null));

create unique index ledger_transaction_principal_idempotency_key_uq
    on ledger_transaction (principal_id, idempotency_key)
    where idempotency_key is not null;
