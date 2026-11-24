# Payment Ledger

A double-entry payment ledger for a fictional small UK fintech, built in Java/Spring Boot test-first:

- Entries are append-only.
- Every Transaction balances to zero.
- Balances are derived, never edited.
- Outbound Faster Payments go through a simulated scheme.
- A Read Model, fed by Kafka through a transactional outbox, powers the dashboards.

> Built a double-entry payment ledger in Java/Spring Boot that never double-charges, never loses an event, never applies one twice and can prove every balance, load-tested at **N** payments a second, deployed on AWS.

## Status

**In progress** (as of 2026-11-21). Milestone M1, the walking skeleton, is built. M2's building blocks (funds checks, Overdraft Limits and Corrections) are under way.

| | |
|---|---|
| Live demo | _not deployed yet_ |
| Load test (N payments/s, p99) | _not run yet_ |
| Grafana dashboard | _not published yet_ |

## Run it locally

You need Docker. From the repository root:

```sh
docker compose up --build
```

This starts Postgres 18, the ledger on http://localhost:8080 and the bare transfer page on http://localhost:5173. The ledger runs with its `local` profile, which opens two Customer Accounts the page knows by id: Account A, funded with £100.00, and Account B, empty. Make an Internal Transfer on the page and both Balances update. `docker compose down -v` stops everything and deletes the data.

To work on the page with hot reload, keep Postgres and the ledger in Compose and run Vite yourself (it proxies `/v1` to the ledger):

```sh
docker compose up --build postgres ledger
cd web && npm install && npm run dev
```

To rebuild the Read Model by replaying Kafka, stop the projector, run it once with `--rebuild` (it builds fresh tables from offset 0, swaps them in and exits; it refuses to run while the projector is consuming), then start it again:

```sh
docker compose stop projector
docker compose run --rm projector --rebuild
docker compose start projector
```

Faster Payments go to the FPS simulator on http://localhost:8081. It accepts 95% of them by default and always rejects a Payee account number ending `0401` with `AC04`. To show failure handling, change its accept rate, black-hole rate (submissions it never answers) and latency while it runs:

```sh
curl -X PUT localhost:8081/admin/settings -H 'Content-Type: application/json' \
  -d '{"acceptRate": 0.5, "blackHoleRate": 0.1, "latencyP50Ms": 200, "latencyP99Ms": 2000}'
```

The same settings start from the `FPSSIM_ACCEPT_RATE`, `FPSSIM_BLACK_HOLE_RATE`, `FPSSIM_LATENCY_P50_MS` and `FPSSIM_LATENCY_P99_MS` environment variables.

### Seed data

The seed CLI in `seed/` loads about 1M synthetic Transactions, booked from 2025-11-01 to 2026-10-31, into an empty ledger database: 5,000 Customers with about 6,500 Customer Accounts, and 200 load-test Accounts with £1,000,000.00 each. It starts from a fixed random seed, so every run generates identical data. It migrates the database, refuses one that already has Transactions, and bulk-loads everything with `COPY` in one database transaction, marked `source=SEED`. The running ledger's outbox publisher then drains the seeded events into Kafka, and the projector builds the Read Model from them. Start from a fresh database (`docker compose down -v`) and seed it before the ledger first starts, since the `local` profile posts a Transaction of its own:

```sh
docker compose up -d postgres
./mvnw -pl seed -am package -DskipTests
java -jar seed/target/seed-0.1.0-SNAPSHOT.jar   # or --url=… --user=… --password=… --customers=…
docker compose up --build
```

All seed data is synthetic. Customer names come from Datafaker with a fixed seed. **Every sort code in seed data is in the range 00-00-00 to 00-99-99, which this project treats as fictional**: the fintech's Customer Accounts are at 00-01-00, and Payees of seeded Faster Payments at the rest of the range. They stand in for real UK sort codes and must never be used to make a real payment.

Tests: `./mvnw verify` for the ledger (Docker must be running, for Testcontainers) and `npm test` in `web/` for the page.

## Docs

- [Specification](docs/spec.md): scenarios, milestones, architecture, targets
- [Glossary](GLOSSARY.md): the domain language
- [Architecture decision records](docs/adr/)

## Stack

- **Back end:** Java 25, Spring Boot 4, Spring Modulith, plain SQL through JdbcClient, Flyway, Postgres 18, Kafka (KRaft)
- **Tests:** JUnit 6, AssertJ, jqwik, Testcontainers
- **Front end:** React, TypeScript, Vite, TanStack Router and Query, MUI
- **Ops:** Gatling, Grafana Cloud with OpenTelemetry, GitHub Actions, Terraform, AWS EC2

## Trade-offs

_To be written as the build progresses._ Decisions that were easy to reverse, and so have no ADR, are explained here:

- Gatling over k6
- MUI over shadcn/ui
- Grafana Cloud over a self-hosted stack
- polling over WebSockets

All data in this project is synthetic.
