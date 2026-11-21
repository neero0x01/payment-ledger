---
status: proposed
---

# Range-partition Entries by month on booking time

**Status: Proposed.** This becomes Accepted in M6, once the EXPLAIN evidence below is recorded.

Entries are range-partitioned by month on `booked_at`, with this index on each partition:

`(account_id, booked_at DESC, entry_seq DESC) INCLUDE (balance_after_pence)`

The As-At Balance query becomes an ordered, index-only scan that reads the newest partition first and stops at the first match.

How partitions are managed:

- Partitions are created 3 months ahead by a scheduled job.
- There is no DEFAULT partition, because rows landing there block creating the real partition later.
- Partitions are never dropped.
- Insert-only autovacuum is tuned so the visibility map stays current and index-only scans keep working.

## Consequences

Unique constraints on a partitioned table must include the partition key, so `(account_id, entry_seq)` can't be unique in the database. The ordered row lock (ADR 0004) guarantees it instead.

## Evidence (to fill in during M6)

EXPLAIN (ANALYZE, BUFFERS) at 1M+ Transactions, **before** (an unpartitioned table with only its primary key) and **after** (partitioned and indexed), for each of:

1. As-At Balance
2. a Statement page
3. the posting write path
