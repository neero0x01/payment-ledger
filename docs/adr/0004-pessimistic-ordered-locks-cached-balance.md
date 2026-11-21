# Pessimistic, ordered row locks on a cached balance row; balance_after on every Entry

Every posting runs at READ COMMITTED and does three things:

- locks each affected Account's `account_balance` row with `SELECT … FOR UPDATE`, **one at a time in ascending account id order**, which makes deadlocks impossible
- runs the funds check against the locked Balance
- writes Entries carrying `entry_seq` and `balance_after`, read with `clock_timestamp()` *after* the locks are taken

The balance row is a cached derivation of the Entries, not a source of truth. The balanced check verifies it against the Entries every 60 s and fully every night.

Why each choice:

- **Locking rather than summing Entries:** summing would scan millions of rows while holding a lock.
- **Pessimistic rather than optimistic versioning:** optimistic versioning turns contention into retry storms.
- **Not SERIALIZABLE:** it aborts transactions unpredictably, they have to be retried, and the behaviour is hard to show.
- **Ordered locks:** they make Scenario 4 ("exactly one succeeds") deterministic.
- **`balance_after`:** it turns an As-At Balance into a single index lookup.

## Consequences

- **The hot account.** Every Faster Payment touches FPS Clearing, so those Payments serialize on its row. This is accepted in v1 and measured by the hot-account load test. The standard fix, sharded clearing sub-accounts, is in the v2 backlog.
- **Uniqueness of `(account_id, entry_seq)`** can't be enforced on the partitioned Entries table (ADR 0010). The lock guarantees it.
- **`now()` would be wrong.** It returns the time the database transaction started, which can order Entries differently from the order in which locks were obtained.
