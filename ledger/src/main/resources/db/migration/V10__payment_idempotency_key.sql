-- A Payment carries the Idempotency-Key it was made with and a SHA-256 hash of its canonical
-- request, unique per principal, so a retry replays the stored Payment instead of making a second
-- one (ADR 0007). The principal is the Customer who made the Payment.

alter table payment
    add column principal_id    uuid,
    add column idempotency_key text,
    add column request_hash    text;

-- Payments made before keys existed belong to the payer's Customer and get a key no client can
-- send again, so they can never be replayed.
update payment
set principal_id    = account.customer_id,
    idempotency_key = 'before-idempotency-keys:' || payment.id,
    request_hash    = ''
from account
where account.id = payment.debtor_account_id;

alter table payment
    alter column principal_id set not null,
    alter column idempotency_key set not null,
    alter column request_hash set not null;

create unique index payment_principal_idempotency_key_uq on payment (principal_id, idempotency_key);
