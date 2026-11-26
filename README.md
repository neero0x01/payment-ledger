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
| Grafana dashboard | [Payment Ledger on Grafana Cloud](https://serenecorridor1126.grafana.net/public-dashboards/9e2f7ac9659f46dc91a7703e887363c0) |

## Run it locally

You need Docker. From the repository root:

```sh
LEDGER_PROFILES=local docker compose --profile web up --build
```

This builds the services from source and starts the whole system: Postgres 18, Kafka, the ledger on http://localhost:8080, the projector, the FPS simulator, Caddy on http://localhost (in front of the ledger), Grafana Alloy, and the bare transfer page on http://localhost:5173. Each container runs under its share of the memory budget (spec §11). `LEDGER_PROFILES=local` runs the ledger with its `local` profile, which opens two Customer Accounts the page knows by id: Account A, funded with £100.00, and Account B, empty. Log in as `local-customer` (password `local-customer`), make an Internal Transfer and both Balances update. Without `LEDGER_PROFILES` the ledger runs with no profile, as it does when deployed, and opens no Accounts of its own. `docker compose down -v` stops everything and deletes the data. Host ports are set by `POSTGRES_PORT`, `LEDGER_PORT`, `FPSSIM_PORT`, `CADDY_HTTP_PORT`, `CADDY_HTTPS_PORT`, `WEB_PORT` and `ALLOY_PORT` if the defaults are taken.

Without `--build`, Compose runs the images published to GHCR (`IMAGE_TAG` picks one; default `latest`). On every push to main, CI builds arm64 images of the ledger, projector and simulator with Jib and pushes them tagged `latest` and with the commit SHA. To build them into your local Docker instead:

```sh
./mvnw -pl ledger,projector,fps-simulator -am package jib:dockerBuild -DskipTests
```

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

### Observability

Grafana Alloy ships metrics, traces and logs to Grafana Cloud (spec §13). It scrapes the ledger's `/actuator/prometheus` (only from inside the box: Caddy and the ledger both refuse it from outside), reads the projector's consumer lag from Kafka, receives the ledger's and the projector's OTLP traces, and tails every container's ECS JSON logs. One trace covers a Payment from its HTTP request through the outbox and Kafka to the projector, and each Payment's log line carries its `paymentId`, `transactionId` and `traceId`.

To ship from your machine, put the Grafana Cloud credentials in `.env` at the repository root (git-ignored):

```sh
GRAFANA_CLOUD_PROM_URL=…        GRAFANA_CLOUD_PROM_USER=…
GRAFANA_CLOUD_LOKI_URL=…        GRAFANA_CLOUD_LOKI_USER=…
GRAFANA_CLOUD_OTLP_ENDPOINT=…   GRAFANA_CLOUD_OTLP_USER=…
GRAFANA_CLOUD_TOKEN=…           # an access policy token with metrics, logs and traces write
```

Without them Alloy still runs but ships nothing. Its UI at http://localhost:12345 shows each pipeline's health. The dashboard is `infra/grafana/payment-ledger.json`; import it into the stack. The published one is linked under Status.

### Seed data

The seed CLI in `seed/` loads about 1M synthetic Transactions, booked from 2025-11-01 to 2026-10-31, into an empty ledger database: 5,000 Customers with about 6,500 Customer Accounts, and 200 load-test Accounts with £1,000,000.00 each. It starts from a fixed random seed, so every run generates identical data. It migrates the database, refuses one that already has Transactions, and bulk-loads everything with `COPY` in one database transaction, marked `source=SEED`. The running ledger's outbox publisher then drains the seeded events into Kafka, and the projector builds the Read Model from them. The seed emits `TransactionPosted` events only, no `PaymentStatusChanged` ones (spec §10), so the dashboard's Payment figures count only live Payments. Start from a fresh database (`docker compose down -v`), seed it before the ledger first starts, and start the ledger without the `local` profile, which posts a Transaction of its own:

```sh
docker compose up -d postgres
./mvnw -pl seed -am package -DskipTests
SEED_LOAD_TEST_PASSWORD='choose-one' java -jar seed/target/seed-0.1.0-SNAPSHOT.jar
# or --url=… --user=… --password=… --customers=… --load-test-password=…
docker compose up --build
```

The default seed has 5,200 Customers in all: the 5,000 that `--customers` sets, plus the 200 load-test Customers who each own one load-test Account.

**Load-test logins.** So the load test can make Payments from the 200 load-test Accounts, the seed gives each load-test Customer a login when it is given a password: `load-test-001` to `load-test-200`, where `load-test-NNN` owns `Load Test Account NNN`. The password comes from `--load-test-password` or, failing that, the `SEED_LOAD_TEST_PASSWORD` environment variable, and every load-test login shares it. There is no default: without a password the seed adds no logins. Choose a fresh one per environment and keep it with the load test's secrets, never in the repository.

All seed data is synthetic. Customer names come from Datafaker with a fixed seed. **Every sort code in seed data is in the range 00-00-00 to 00-99-99, which this project treats as fictional**: the fintech's Customer Accounts are at 00-01-00, and Payees of seeded Faster Payments at the rest of the range. They stand in for real UK sort codes and must never be used to make a real payment.

### Load test

The Gatling simulations in `loadtest/` (spec §12) make Payments as the 200 load-test Customers, so they need a seeded ledger with load-test logins (above). `MainMixSimulation` runs the main mix: 70% Internal Transfers between random load-test Accounts, 15% Faster Payments, 5% retries with a reused Idempotency-Key, 5% Payments Rejected for Insufficient Funds and 5% Balance and Statement reads. `HotAccountSimulation` runs 100% Faster Payments, which all credit FPS Clearing, to measure contention on its balance row (ADR 0004). Before the traffic starts, each simulation logs every load-test Customer in once and reuses the sessions. It also brings load-test Accounts 191 to 200 just under £1,000,000.00, the most one Payment may be. Only Insufficient Funds Payments are made from those Accounts, and nothing pays into them.

```sh
LOADTEST_PASSWORD='the-seed-load-test-password' ./mvnw -f loadtest gatling:test \
  -Dgatling.simulationClass=com.paymentledger.loadtest.MainMixSimulation \
  -DbaseUrl=http://localhost:8080 -DpaymentsPerSecond=250 -DrampUpSeconds=60 -DholdSeconds=600
```

The defaults are `http://localhost:8080`, 50 payments a second, a 30 s ramp and a 60 s hold. A run fails its Gatling assertions unless every `POST /v1/payments` request has p99 under 200 ms and under 0.1% of requests fail. The report is written to `loadtest/target/gatling/`. The third pass mark, outbox lag under 1 s, is on the Grafana dashboard (`ledger_outbox_oldest_age_seconds`). **N** is the highest rate that passes all three for a 10-minute hold, measured from the load generator in eu-west-2, never from a laptop.

### Tests

`./mvnw verify` runs the ledger's tests (Docker must be running, for Testcontainers) and `npm test` in `web/` runs the page's. The Playwright smoke test (log in, pay, see Completed) runs against the stack in Compose: start it as above, with `LEDGER_PROFILES=local`, then `npx playwright install chromium` once and `npm run smoke` in `web/` (`SMOKE_BASE_URL` points it elsewhere than http://localhost:5173).

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
