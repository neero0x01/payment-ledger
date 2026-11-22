---
status: accepted
---

# The Idempotency-Key lives on the Payment row; the database serializes in-flight retries

**Status: Accepted** in M3. The Payment is inserted with `on conflict (principal_id, idempotency_key) do nothing`: a concurrent retry waits on the unique index, inserts nothing once the first commits, and replays the stored Payment. That is the uniqueness violation below, handled in SQL rather than as an exception that would abort the transaction.

The Idempotency-Key and a SHA-256 hash of the canonical request are columns on `payment`, with a unique index on `(principal_id, idempotency_key)`. The Payment is inserted **first** in the posting transaction, before any balance lock.

That ordering gives three behaviours:

- **A concurrent retry** blocks on the unique index until the first transaction commits, then gets a uniqueness violation and replays the stored Payment. No `409` and no separate in-flight lock table are needed.
- **A waiting retry holds no locks**, so it can't deadlock.
- **Business rejections are stored as Payments** (Rejected with a reason), so they replay too.

The rest follows the IETF Idempotency-Key header draft:

- A reused key with a different body gets `422`.
- A replay returns the Payment's current state with `Idempotent-Replayed: true`.
- Keys never expire, because they live as long as their Payment.

## Corrections

Corrections follow the same rules. The key, the Operator's id as `principal_id` and the request hash are columns on the Correction's own `ledger_transaction` row, with a partial unique index on `(principal_id, idempotency_key)`, and the row is inserted with the same `on conflict … do nothing`. A replay is looked up before the already-reversed check, so a retried Reversing Correction replays instead of answering `409`.

## Considered options

- **A separate idempotency table with stored responses and a TTL**, as Stripe does: more moving parts, and it replays a stale snapshot instead of the current state.
