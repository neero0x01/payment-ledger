---
status: accepted
---

# A hand-rolled polling transactional outbox

**Status: Accepted** in M4. Posting appends a `TransactionPosted` row to the `outbox` table in the same database transaction as the Transaction, and the `outbox` module's publisher sends it to `ledger.transaction-posted.v1` as set out below. The Kafka-outage test proves it: with Kafka stopped, 100 Payments all return `201` and the lag gauge reaches 100; with Kafka restarted, the lag drains to 0. Payment status events follow when Payments change status.

Events are written to an `outbox` table in the same database transaction as the Transaction or Payment change. A publisher inside the ledger works like this:

- it polls every 200 ms, and each poll first takes a transaction-level advisory lock (`pg_try_advisory_xact_lock`), so only one publisher sends at a time however many ledger instances run; a poll that doesn't get the lock does nothing
- it locks the oldest rows with `SELECT … ORDER BY id LIMIT 500 FOR UPDATE` and sends them in that order
- it sends with Kafka's idempotent producer and `acks=all`
- it marks each row published

One publisher at a time keeps the events for a key in the order they were written. With `SKIP LOCKED` and several publishers, one publisher could skip a row another holds and send a later event for the same key first.

This means a Payment never depends on Kafka being up. It also means an event can never be lost, because it commits together with the change it describes, or not at all.

Delivery is **at least once**: a crash after sending but before marking produces a duplicate, and consumers dedupe on `eventId` in the same transaction as their update. The claim is therefore "never loses an event, never applies one twice", not "exactly-once delivery".

## Considered options

- **Dual write (commit to Postgres, then send to Kafka):** loses events on a crash between the two steps.
- **Debezium CDC:** adds a Kafka Connect container (RAM on a 4 GiB box) and hides the pattern this project exists to show.
- **Spring Modulith's event publication registry with Kafka externalization:** a good production choice, but it also hides the pattern.
