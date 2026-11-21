# A hand-rolled polling transactional outbox

Events are written to an `outbox` table in the same database transaction as the Transaction or Payment change. A publisher inside the ledger works like this:

- it polls every 200 ms with `SELECT … FOR UPDATE SKIP LOCKED LIMIT 500`
- it sends with Kafka's idempotent producer and `acks=all`
- it marks each row published

This means a Payment never depends on Kafka being up. It also means an event can never be lost, because it commits together with the change it describes, or not at all.

Delivery is **at least once**: a crash after sending but before marking produces a duplicate, and consumers dedupe on `eventId` in the same transaction as their update. The claim is therefore "never loses an event, never applies one twice", not "exactly-once delivery".

## Considered options

- **Dual write (commit to Postgres, then send to Kafka):** loses events on a crash between the two steps.
- **Debezium CDC:** adds a Kafka Connect container (RAM on a 4 GiB box) and hides the pattern this project exists to show.
- **Spring Modulith's event publication registry with Kafka externalization:** a good production choice, but it also hides the pattern.
