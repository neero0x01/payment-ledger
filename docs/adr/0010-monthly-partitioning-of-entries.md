---
status: accepted
---

# Range-partition Entries by month on booking time

**Status: Accepted** in M6, with the EXPLAIN evidence below.

Entries are range-partitioned by month on `booked_at`, with this index on each partition:

`(account_id, booked_at DESC, entry_seq DESC) INCLUDE (balance_after_pence)`

The As-At Balance query becomes an ordered, index-only scan that reads the newest partition first and stops at the first match.

How partitions are managed:

- A partition holds one calendar month in UTC and is named `entry_YYYY_MM`. Flyway (`V21__monthly_entry_partitions.sql`) creates every month from November 2025, the seed's first, through three months ahead.
- After that, a scheduled job in `posting` creates partitions 3 months ahead. It runs when the ledger starts and then daily. It takes the current month from the database clock, the clock Booking Times come from. It calls `create_entry_partition(month)`, a `SECURITY DEFINER` function owned by the schema owner, so `ledger_app` can create partitions without owning tables.
- The gauge `ledger.entry.partition.next.month.missing` reads 1 when next month has no partition, which leaves weeks to act before Entries would be refused.
- There is no DEFAULT partition, because rows landing there block creating the real partition later. An Entry booked in a month with no partition is refused.
- Partitions are never dropped.
- Insert-only autovacuum is tuned so the visibility map stays current and index-only scans keep working. Each partition has `autovacuum_vacuum_insert_scale_factor = 0` and `autovacuum_vacuum_insert_threshold = 10000`, so it is vacuumed after every 10,000 inserts. With the default (20% of the table), the current month's visibility map would fall further behind as the month grows. Postgres can't set storage parameters on a partitioned table, so `create_entry_partition` sets them on each partition.

## Consequences

Unique constraints on a partitioned table must include the partition key, so `(account_id, entry_seq)` can't be unique in the database. The ordered row lock (ADR 0004) guarantees it instead. The primary key is `(id, booked_at)`.

The double-entry guard (V2) used to find a Transaction's Entries through `transaction_id` alone, which would probe every month's index on every commit, at a cost that grows each month. The guard now also makes sure every Entry is booked at its Transaction's Booking Time, and looks Entries up by `(transaction_id, booked_at)`, so runtime pruning leaves one partition.

## Evidence

Measured on Postgres 18 (Docker, `shared_buffers=256MB`) with the seed CLI at `--customers=5200`: **1,015,657 Transactions** and 2,031,314 Entries across 12 monthly partitions (Nov 2025 to Oct 2026). **Before** is the shape before V21: one unpartitioned table with its primary key on `id`, plus the `transaction_id` index the double-entry guard uses. **After** is the V21 shape: partitioned and indexed. Both hold the same rows and were `VACUUM ANALYZE`d. The Account is the busiest Customer Account (251 Entries; the median is 189). Each query ran once to warm the cache, and the plans below are from the second run, trimmed to the lines that matter.

| Query | Before | After |
|---|---|---|
| As-At Balance | 56.2 ms, 27,175 buffers, parallel seq scan + sort | **0.13 ms, 4 buffers**, index-only scan, no sort, 0 heap fetches |
| Statement page (51 rows) | 44.5 ms, 27,115 buffers, parallel seq scan + sort | **0.45 ms, 72 buffers**, three partitions' indexes |
| Write path: Entry insert | 0.30 ms, 29 buffers | 0.44 ms, 87 buffers |
| Write path: double-entry guard lookup | 0.014 ms, 4 buffers | 0.035 ms, 1 buffer, 15 of 16 partitions pruned |

### 1. As-At Balance

```sql
select balance_after_pence from entry
where account_id = $1 and booked_at <= '2026-05-15T12:00:00Z'
order by booked_at desc, entry_seq desc
limit 1;
```

Before:

```
Limit (actual time=51.746..56.158 rows=1.00 loops=1)
  Buffers: shared hit=14509 read=12666
  ->  Gather Merge (actual time=51.745..56.156 rows=1.00 loops=1)
        Workers Launched: 2
        ->  Sort (actual time=47.732..47.732 rows=1.00 loops=3)
              Sort Key: booked_at DESC, entry_seq DESC
              Sort Method: top-N heapsort  Memory: 25kB
              ->  Parallel Seq Scan on entry_before (actual time=9.222..47.529 rows=42.00 loops=3)
                    Filter: ((booked_at <= '2026-05-15 12:00:00+00') AND (account_id = '0199fe9e-…'))
                    Rows Removed by Filter: 677063
Execution Time: 56.183 ms
```

After: the Append walks the partitions newest first and stops at the first row. Every older partition is `never executed`.

```
Limit (actual time=0.046..0.047 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Append (actual time=0.046..0.046 rows=1.00 loops=1)
        ->  Index Only Scan using entry_2026_05_account_id_booked_at_entry_seq_balance_after__idx on entry_2026_05
              (actual time=0.045..0.045 rows=1.00 loops=1)
              Index Cond: ((account_id = '0199fe9e-…') AND (booked_at <= '2026-05-15 12:00:00+00'))
              Heap Fetches: 0
              Buffers: shared hit=4
        ->  Index Only Scan using entry_2026_04_…_idx on entry_2026_04 (never executed)
        …   (entry_2026_03 down to entry_2025_11, all never executed)
Execution Time: 0.130 ms
```

### 2. A Statement page

This is the Statement's keyset query: one Account, a three-month date range, newest first, 50 lines plus one to tell whether there is a next page. The live Statement endpoint runs this query on the Read Model's `statement_line` (index `(account_id, booked_at, entry_seq)`), which the projector owns. Here the query runs on Entries, where partitioning changes the plan.

```sql
select transaction_id, booked_at, entry_seq, side, amount_pence, balance_after_pence from entry
where account_id = $1
  and booked_at >= '2026-03-01T00:00:00Z' and booked_at < '2026-06-01T00:00:00Z'
  and (booked_at, entry_seq) < ('infinity', 0)
order by booked_at desc, entry_seq desc
limit 51;
```

Before:

```
Limit (actual time=39.730..44.416 rows=51.00 loops=1)
  Buffers: shared hit=15013 read=12102 written=164
  ->  Gather Merge
        ->  Sort  Sort Key: booked_at DESC, entry_seq DESC
              ->  Parallel Seq Scan on entry_before (actual time=14.331..37.503 rows=19.67 loops=3)
                    Rows Removed by Filter: 677085
Execution Time: 44.465 ms
```

After: the date range prunes to three partitions. The planner chooses a bitmap scan of each one's index and sorts the 59 rows.

```
Limit (actual time=0.428..0.432 rows=51.00 loops=1)
  Buffers: shared hit=72
  ->  Sort (actual time=0.427..0.429 rows=51.00 loops=1)
        Sort Method: quicksort  Memory: 32kB
        ->  Append (actual time=0.050..0.402 rows=59.00 loops=1)
              ->  Bitmap Heap Scan on entry_2026_03  (rows=20, Buffers: shared hit=22)
                    ->  Bitmap Index Scan on entry_2026_03_account_id_booked_at_entry_seq_balance_after__idx
              ->  Bitmap Heap Scan on entry_2026_04  (rows=24, Buffers: shared hit=26)
              ->  Bitmap Heap Scan on entry_2026_05  (rows=15, Buffers: shared hit=18)
Execution Time: 0.451 ms
```

### 3. The posting write path

The parts of a posting that touch Entries: the Entry insert as `JdbcPosting` sends it, and the double-entry guard's lookup, which runs once for each inserted row when the Transaction commits.

```sql
insert into entry (transaction_id, account_id, side, amount_pence, booked_at, entry_seq, balance_after_pence)
select id, $1, 'CREDIT', 100, booked_at, $2, $3 from ledger_transaction where id = $4;
```

Before:

```
Insert on entry_before (actual time=0.088..0.088 rows=0.00 loops=1)
  Buffers: shared hit=29
  ->  Index Scan using ledger_transaction_pkey on ledger_transaction (actual time=0.024..0.025 rows=1.00 loops=1)
Trigger for constraint entry_before_transaction_id_fkey: time=0.083 calls=1
Trigger for constraint entry_before_account_id_fkey: time=0.125 calls=1
Execution Time: 0.303 ms
```

After: the row is routed to the current month's partition, and the insert maintains one more index (the As-At index).

```
Insert on entry (actual time=0.200..0.200 rows=0.00 loops=1)
  Buffers: shared hit=87
  ->  Index Scan using ledger_transaction_pkey on ledger_transaction (actual time=0.045..0.046 rows=1.00 loops=1)
Trigger for constraint entry_account_id_fkey1 on entry_2026_11: time=0.190 calls=1
Trigger for constraint entry_transaction_id_fkey1 on entry_2026_11: time=0.038 calls=1
Execution Time: 0.441 ms
```

The guard's lookup, before (V2, by `transaction_id`):

```
Aggregate (actual time=0.005..0.005 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using entry_before_transaction_idx on entry_before (actual time=0.002..0.002 rows=1.00 loops=1)
Execution Time: 0.014 ms
```

Looking up by `transaction_id` alone on the partitioned table probes all 16 partitions' indexes: 0.47 ms and 37 buffers, plus 1.2 ms of planning. The cost grows with every month. So the guard looks up by `(transaction_id, booked_at)` instead. PL/pgSQL settles on a generic plan, and that plan prunes at run time:

```
Aggregate (actual time=0.035..0.035 rows=1.00 loops=1)
  Buffers: shared hit=1
  ->  Append (actual time=0.027..0.028 rows=1.00 loops=1)
        Subplans Removed: 15
        ->  Seq Scan on entry_2026_11 (actual time=0.026..0.027 rows=1.00 loops=1)
              Filter: ((transaction_id = $1) AND (booked_at = $2))
Execution Time: 0.132 ms
```

The current month's partition was nearly empty at the time, so it was seq-scanned. Once that month fills, the lookup uses its `transaction_id` index.

**Verdict:** the read paths get two orders of magnitude faster, and they no longer grow with the table: the As-At Balance reads 4 buffers whatever the ledger's size. The write path costs about 0.15 ms more per Entry, mostly from maintaining the extra index and routing the row, which is small next to the round trips in a posting.
