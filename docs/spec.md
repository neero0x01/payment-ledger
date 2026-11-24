# Payment Ledger: v1 specification

Status: agreed 2026-11-21. Capitalised domain terms are defined in [GLOSSARY.md](../GLOSSARY.md). Decisions with lasting trade-offs are recorded in [docs/adr/](adr/).

## 1. Problem and claim

A small UK fintech has to move its Customers' money without ever double-charging, losing a record or being unable to explain a Balance. This project builds that fintech's double-entry ledger. In it:

- Entries are append-only.
- Every Transaction balances to zero.
- Balances are derived, never edited.
- Outbound Faster Payments go through a simulated scheme.
- A Read Model, fed by Kafka, powers the dashboards.

The claim the finished project has to back up:

> Built a double-entry payment ledger in Java/Spring Boot that never double-charges, never loses an event, never applies one twice and can prove every Balance, load-tested at **N** payments a second, deployed on AWS.

**N** is defined in [`12](#12-load-test-plan).

It is a portfolio project aimed at backend Java/Spring Boot roles in UK fintech and banking. It goes deep on four things: **Kafka with a transactional outbox**, **CQRS**, **Postgres tuning** and **observability**. All data is synthetic.

## 2. Domain rules (invariants)

1. Every Transaction has at least two Entries, and its Debits total equals its Credits total. This is enforced in the domain model and again by a deferred database trigger at commit.
2. Each Entry has a positive amount in pence, a side (Debit or Credit), its Account, a Booking Time, the Account's `entry_seq` and the Account's `balance_after`.
3. Entries and Transactions are never updated or deleted. The ledger's database role has only `INSERT` and `SELECT` on those tables. Payments and outbox rows are mutable.
4. Every Account has a Normal Side, and its Balance is reported on that side. Customer Accounts are credit-normal; Funding and FPS Clearing are debit-normal.
5. Available Balance = Balance + Overdraft Limit. A Payment is allowed only if its amount ≤ Available Balance. Internal Accounts have no funds check.
6. Money is GBP only, stored as a `long` number of pence inside a `Money` value object.
7. A Payment leads to zero or more Transactions. A Transaction has zero or one originating Payment. Corrections have no Payment.
8. A Transaction can be reversed at most once, by either a Reversal or a Reversing Correction.
9. No Correction may reference a Transaction whose Payment is still Pending.
10. Booking Time is the only timeline. An As-At Balance reflects what the ledger had recorded at that moment, including mistakes that were corrected later.

## 3. v1 acceptance scenarios

Scenario numbers are stable IDs; numbers 5 and 8 are in the v2 backlog. Each criterion is tagged with the milestone that turns it green. Amounts are in GBP.

### Scenario 1: Internal Transfer [M1]

- **Given** Customer Account A has a Balance of £100.00 and B has £0.00, **when** the Customer pays £30.00 from A to B, **then** the response is `201` with the Payment Completed, exactly one Transaction is posted (Debit A £30.00 / Credit B £30.00), A's Balance is £70.00 and B's is £30.00. [M1]
- **Given** any of these requests, **when** it is sent, **then** the response is a `400` or `404` problem, no Payment is stored and the Idempotency-Key is not used up [M1]:
  - an amount ≤ 0
  - an amount above £1,000,000.00
  - the same Account as payer and Payee
  - an unknown Payee
- **Given** the payer Account belongs to a different Customer, **when** the Customer tries to pay from it, **then** the response is `403` and nothing is stored. [M3]

### Scenario 2: Idempotent retry [M3]

- **Given** a Payment was created with Idempotency-Key K, **when** the same principal sends the same body with K again, **then** the response is `200` with header `Idempotent-Replayed: true` and the Payment as it is now, and no new Transaction is posted.
- **Given** a Faster Payment first answered as Pending has since Completed, **when** it is retried, **then** the replay shows Completed.
- **Given** a Payment was Rejected for Insufficient Funds, **when** it is retried after the Account has been funded, **then** the replay still shows Rejected and nothing is posted.
- **Given** K was used with one body, **when** K is sent with a different body, **then** the response is `422` with problem type `/problems/idempotency-key-reused` and nothing is posted.
- **Given** two identical requests with K arrive at the same moment, **when** both are processed, **then** exactly one Payment and at most one Transaction exist, and both responses describe the same Payment.
- **Given** two different principals each send key K, **when** both are processed, **then** they create two independent Payments.
- **Given** `POST /v1/payments` has no Idempotency-Key header, **when** it is sent, **then** the response is `400`.
- **Given** Kafka is down, **when** a retry arrives, **then** it is replayed normally, because idempotency lives entirely in Postgres.

### Scenario 3: Insufficient funds and overdraft [M2]

- **Given** A has a Balance of £50.00 and an Overdraft Limit of £100.00, **when** the Customer pays £150.00, **then** the Payment is Completed and A's Balance is −£100.00.
- **Given** the same Account, **when** the Customer pays £150.01 instead, **then** the response is `201` with the Payment Rejected with reason `INSUFFICIENT_FUNDS`, no Transaction is posted and Balances are unchanged.
- **Given** FPS Clearing or Funding would go below zero, **when** a Transaction posts to it, **then** it is allowed, because Internal Accounts have no funds check.
- **Given** a Correction has pushed A beyond its Overdraft Limit, **when** A tries to pay out, **then** every outbound Payment from A is Rejected with `INSUFFICIENT_FUNDS` until it is funded again.

### Scenario 4: Concurrent payments [M3]

- **Given** A has £100.00 and no overdraft, **when** two £70.00 Payments are released at the same moment (by a `CyclicBarrier`, repeated 50 times), **then** exactly one is Completed and one is Rejected with `INSUFFICIENT_FUNDS`, A ends at £30.00 and the ledger is balanced.
- **Given** A has £100.00, **when** 20 Payments of £10.00 run at once, **then** exactly 10 are Completed.
- **Given** A→B and B→A Payments run at once 100 times, **when** they finish, **then** there are no deadlock errors.
- **Given** row locking is switched off, **when** the suite runs, **then** it fails. This red run is committed as TDD evidence.

### Scenario 6: Outbound Faster Payment [M5]

- **Given** A has £100.00, **when** the Customer pays £40.00 to an external sort code and account number, **then**:
  - the response is `201` with the Payment Pending
  - one Transaction is posted (Debit A / Credit FPS Clearing) and A's Balance is £60.00
  - after commit, a Scheme Submission with a new End-to-End Id is sent to the simulator
- **Given** the Scheme Outcome is accepted (`ACSC`), **then** the Payment is Completed and no further Transaction is posted. Money stays in FPS Clearing, because settlement is out of scope.
- **Given** the Scheme Outcome is rejected (`RJCT`, for example `AC04`), **then**:
  - the Payment is Rejected with reason `SCHEME_REJECTED` and the scheme reason code is stored
  - a Reversal is posted (Debit FPS Clearing / Credit A) that references the original Transaction
  - A is back to £100.00
- **Given** a duplicate `RJCT` arrives, **then** the response is `200` and exactly one Reversal exists.
- **Given** `ACSC` arrives and then `RJCT`, **then** the second outcome is ignored and logged and alerted as a scheme anomaly.
- **Given** no Scheme Outcome arrives within 15 s, **then** the submission is re-sent with the same End-to-End Id, and the simulator returns its stored outcome.
- **Given** no Scheme Outcome arrives within 5 min, **then** the Payment stays Pending, is flagged as a Stuck Payment on the dashboard and in alerts, and is never reversed automatically.
- **Given** a Faster Payment would fail the funds check, **then** it is Rejected with `INSUFFICIENT_FUNDS` and nothing is submitted.
- **Given** a callback names an unknown End-to-End Id, **then** the response is `404`. **Given** a callback has an invalid HMAC signature, **then** the response is `401`.
- **Given** a Payee account number ends in `0401`, **then** the simulator always rejects it with `AC04`. This gives tests and demos a deterministic rejection.

### Scenario 7: Immutable Corrections [M2; Operator role M3]

- **Given** Transaction T posted twice by mistake, **when** an Operator posts a Reversing Correction of T with reason `DUPLICATE_POSTING` and a note, **then** a new Transaction is posted that mirrors T's Entries with the sides swapped, linked to T and recording the Operator's id. T is unchanged and the Balances are restored. [M2]
- **Given** T has already been reversed, by either a Reversal or a Reversing Correction, **when** a second reversal is attempted, **then** the response is `409` with problem type `/problems/already-reversed`. [M2]
- **Given** a Customer is owed a £5.00 goodwill payment, **when** an Operator posts an Adjustment with reason `GOODWILL`, **then** Corrections is debited £5.00 and the Customer Account is credited £5.00. [M2]
- **Given** T belongs to a Payment that is still Pending, **when** any Correction references T, **then** the response is `409` with problem type `/problems/payment-pending`. [M2]
- **Given** T was booked in an earlier month, **when** it is corrected, **then** the Correction is booked now, into the current month, and T's month is not touched. [M2]
- **Given** a Correction request, **when** it has no Idempotency-Key, **then** the response is `400`. Repeat requests behave as in Scenario 2. [M3]
- **Given** a Customer, **when** they try to post a Correction, **then** the response is `403`. [M3]

### Scenario 9: As-At Balance [M3]

- **Given** A's Entries were booked at different moments, **when** the As-At Balance at T is requested, **then** it equals the `balance_after` of A's latest Entry with Booking Time ≤ T, taking the highest `entry_seq` when times tie.
- **Given** T is before A's first Entry, **then** the As-At Balance is £0.00. **Given** T is in the future, **then** the response is `400`.
- **Given** an error was booked on 2026-10-30 and corrected on 2026-11-05, **when** the As-At Balance at 2026-11-01 is requested, **then** it includes the error.
- **Given** two transactions on A started in one order but got A's lock in the other, **when** they commit, **then** Booking Time and `entry_seq` still follow the order in which the lock was obtained, because `booked_at` is read from `clock_timestamp()` after the lock.
- **Given** 1M+ Transactions are loaded, **when** the As-At Balance is queried, **then** the query runs as an index-only scan that stops at the first matching row, shown in ADR 0010. [M6]

### Scenario 10: Outbox → Kafka → Read Model [M4, completed in M5]

- **Given** a Transaction is posted, **then** a `TransactionPosted` outbox row is written in the same database transaction and published to `ledger.transaction-posted.v1`. [M4]
- **Given** Kafka is stopped, **when** 100 Payments are made, **then** all 100 return `201` and outbox lag reaches 100. **When** Kafka is restarted, **then** lag drains to 0. [M4] The Read Model then holds exactly those 100 Transactions. [M5]
- **Given** the publisher crashes after sending but before marking a row published, **then** the event appears on the topic twice, and the projector applies it once. [M5]
- **Given** events arrive out of order, **then** the Read Model Balance equals the `balance_after` of the highest `entry_seq` applied. [M5]
- **Given** a Payment is Rejected for Insufficient Funds, **then** only `payments.payment-status-changed.v1` is emitted. [M4]
- **Given** `projector --rebuild` is run, **then** the rebuilt Read Model equals the live one. [M5]
- **Given** an HTTP request posts a Payment, **then** one trace covers it from the request through the outbox and Kafka to the projector. [M6]

## 4. Milestones and definition of done

The estimate is about 2 working weeks. If time runs short, the first thing cut is the Operator Corrections UI; Corrections then stay API-only and tested.

| # | Milestone | Done when | Demoable |
|---|---|---|---|
| M1 | TDD basics + walking skeleton | Scenario 1 [M1] criteria green. Maven multi-module, Flyway, CI green on PRs, `docker compose up` works locally. A bare transfer page → API → Postgres. | A transfer through a web page on localhost |
| M2 | DDD building blocks | `Money`, the Transaction aggregate and domain events, with jqwik property tests (every generated Transaction balances). Scenarios 3 and 7 [M2] green. ADR 0003 accepted. | Rejections with reasons; Corrections via the API |
| M3 | Transactions, isolation, idempotency | Spring Security with the CUSTOMER and OPERATOR roles. Scenarios 2, 4 and 9 green, plus the [M3] criteria of 1 and 7. ADRs 0004 and 0007 accepted. | The concurrency suite; As-At Balance |
| M4 | Event-driven | The outbox publisher; the Kafka-down test; JSON Schema contract tests. Scenario 10 [M4] green. ADRs 0005 and 0006 accepted. | Payments succeed with Kafka stopped |
| M5 | CQRS + hexagonal | The projector, the `readmodel` database, the `views` module and rebuild. The simulator behind the `PaymentScheme` port. Scenarios 6 and 10 green. Screens 1–3. ADR 0008 accepted. | A Faster Payment rejected → Reversal, live in the UI |
| M6 | Database tuning + observability + deploy | Seed data; partitions and indexes; ADR 0010 accepted with EXPLAIN numbers; Grafana Cloud dashboards; the balanced check; Gatling results in the README; Terraform; deploy; demo login; screens 0, 4 and 5; Playwright smoke test. | Live link and published numbers |

**v1 is shipped** (`v1.0.0`) when all eight scenarios pass in CI, the load test numbers are published in the README and the app is live at `ledger.<your-domain>`.

## 5. Architecture

```
web (Cloudflare Pages) ──HTTPS──▶ Caddy (EC2) ──▶ ledger API (modular monolith)
                                                   │  posting / payments / accounts / outbox / views
                                                   │        │ outbox rows (same DB transaction)
                                         Postgres: ledger ──┘        │ polling publisher
                                                   ▲                 ▼
                       fps-simulator ◀──pacs.008-lite──┤          Kafka (KRaft, single node)
                             └──pacs.002-lite callback─┘                 │
                                                                         ▼
                                            Postgres: readmodel ◀── projector (headless)
                                                   ▲
                                         views module reads (read-only DataSource)
```

### Repository layout

- `ledger/`: Spring Boot modular monolith, with boundaries verified by Spring Modulith
- `fps-simulator/`: Spring Boot stand-in for the Faster Payments Scheme
- `projector/`: Spring Boot Kafka consumer that writes the Read Model
- `seed/`: deterministic data generator (CLI)
- `loadtest/`: Gatling simulations (Java DSL)
- `web/`: Vite + React + TypeScript + TanStack Router + TanStack Query + MUI
- `infra/`: Docker Compose, Caddyfile, Terraform, `chaos/` scripts
- `docs/`: this spec, ADRs, load test reports

### Ledger modules

| Module | Owns | Depends on |
|---|---|---|
| `accounts` | Customers; Customer and Internal Accounts; sort codes; Overdraft Limits; Demo Sandbox creation | `posting` (for sandbox funding) |
| `posting` | Transactions, Entries, balance rows, locking, funds check, Reversals, Corrections, As-At Balance, partition maintenance, the balanced check | `accounts` (read-only), `outbox` |
| `payments` | The Payment lifecycle, idempotency, the `PaymentScheme` port with its HTTP adapter, the scheme dispatcher, the callback endpoint | `posting`, `accounts`, `outbox` |
| `outbox` | The outbox table API (`append`) and the polling publisher | – |
| `views` | Read endpoints backed by the `readmodel` database | – |

The Spring Modulith `ApplicationModules.verify()` test runs in CI.

### Events (Kafka)

Every event is JSON in a shared envelope, with a JSON Schema in `docs/schemas/` and contract tests on both sides. A breaking change means a new `.vN` topic.

```json
{ "eventId": "uuid", "type": "TransactionPosted", "schemaVersion": 1,
  "occurredAt": "2026-11-21T10:15:30.123Z", "payload": { ... } }
```

| Topic | Key | Payload | Retention |
|---|---|---|---|
| `ledger.transaction-posted.v1` | Transaction id | Transaction id, kind, Booking Time, Payment id?, corrects id?, Entries [account id, side, amount, entry_seq, balance_after] | Forever |
| `payments.payment-status-changed.v1` | Payment id | Payment id, type, status, Rejection Reason?, scheme reason code?, amount | 30 days |

- Delivery is at least once. The projector records `eventId` in `processed_event` in the same database transaction as its update.
- Order across events doesn't matter: an Entry is applied only if its `entry_seq` is higher than the last one applied for that Account.
- The `traceparent` is stored on the outbox row and sent as a Kafka header.

### Ledger ⇄ simulator (HTTP)

- **Submission:** `POST /scheme/v1/submissions` with a slimmed-down pacs.008: End-to-End Id, debtor sort code and account, creditor sort code, account and name, amount, reference. The simulator answers `202`.
  - It dedupes on End-to-End Id.
  - It stores outcomes in its own `fpssim` database.
- **Outcome:** `POST /v1/scheme/callbacks` on the ledger with a slimmed-down pacs.002: End-to-End Id, status `ACSC` or `RJCT`, and a reason code (`AC01`, `AC04`, `AG01` or `MS03`). Callbacks are HMAC-SHA256 signed.
- **Dispatcher:** polls Pending Faster Payments with no submission, or whose last attempt was more than 15 s ago. It marks a Payment as stuck after 5 min, and keeps re-sending a Stuck Payment with the same End-to-End Id once a minute until the scheme answers.
- **Simulator settings** (environment variables, plus an admin endpoint):
  - accept/reject rate (default 95/5)
  - black-hole rate, where no outcome is ever sent (default 0)
  - latency p50/p99 (default 200 ms / 2 s)
- **Tests:** unit tests use an in-memory fake `PaymentScheme`; one end-to-end test runs the real simulator container.

## 6. Data model sketch

### Ledger database (`ledger`)

| Table | Columns (sketch) | Notes |
|---|---|---|
| `customer` | id uuidv7, display_name, demo bool, created_at | |
| `account` | id uuidv7, kind (CUSTOMER/INTERNAL), customer_id?, name, sort_code?, account_number?, normal_side, overdraft_limit_pence, created_at | Overdraft is set by seed data only in v1 |
| `account_balance` | account_id PK, balance_pence, last_entry_seq, updated_at | Cached derivation and lock target |
| `payment` | id uuidv7, principal_id, idempotency_key, request_hash, type, debtor_account_id, payee (account_id? / sort_code, account_number, name), amount_pence, reference, status, rejection_reason?, scheme_reason_code?, end_to_end_id?, submission_attempts, last_submitted_at?, stuck bool, created_at, completed_at? | Unique (principal_id, idempotency_key) |
| `ledger_transaction` | id uuidv7, kind (PAYMENT/REVERSAL/CORRECTION/FUNDING), booked_at, payment_id?, reverses_transaction_id?, correction_reason?, correction_note?, operator_id?, principal_id?, idempotency_key?, source (LIVE/SEED) | Not partitioned. Partial unique index on reverses_transaction_id |
| `entry` | id bigint identity, transaction_id, account_id, side (D/C), amount_pence > 0, booked_at, entry_seq, balance_after_pence | **Range-partitioned by month on booked_at**. PK (id, booked_at). (account_id, entry_seq) cannot be unique on a partitioned table; the balance-row lock guarantees it |
| `outbox` | id bigint identity, event_id uuid, topic, msg_key, payload jsonb, traceparent, created_at, published_at? | Polled with `FOR UPDATE SKIP LOCKED` |

- A deferred constraint trigger checks Σdebits = Σcredits per Transaction at commit.

**Posting algorithm.** All steps run in one database transaction at READ COMMITTED:

1. Insert the Payment, if there is one. A concurrent retry waits here on the unique index.
2. `SELECT … FOR UPDATE` every affected `account_balance` row, one at a time, in ascending account id order.
3. Run the funds check against Available Balance.
4. Read `clock_timestamp()` as `booked_at`.
5. Insert the Transaction and its Entries, each with `entry_seq` = last + 1 and `balance_after`.
6. Update the balance rows.
7. Append outbox rows.
8. Commit.

### Read Model database (`readmodel`, same Postgres instance)

`account_balance_view`, `statement_line` (index on account_id, booked_at, entry_seq), `payment_summary`, `dashboard_minute` (counts and value by outcome and Rejection Reason), `processed_event` and `projection_checkpoint`.

### Simulator database (`fpssim`)

`submission` (end_to_end_id PK, received_at, outcome, reason_code, callback_sent_at).

## 7. API

All paths are under `/v1` (Spring Framework 7 API versioning). Errors are RFC 9457 `application/problem+json` with stable `type` URIs under `/problems/`. The OpenAPI 3.1 document is generated by springdoc, and Swagger UI is public at `/docs`.

| Method and path | Who | Notes |
|---|---|---|
| `POST /payments` | CUSTOMER | Idempotency-Key required. `payee` is `{accountId}` or `{sortCode, accountNumber, name}`. Returns `201` with the Payment in any status. A replay returns `200` |
| `GET /payments/{id}` | owner, OPERATOR | Write side |
| `GET /accounts`, `GET /accounts/{id}` | CUSTOMER (own), OPERATOR (all) | Read Model |
| `GET /accounts/{id}/balance?asAt=` | owner, OPERATOR | Write side, authoritative |
| `GET /accounts/{id}/statement?from&to&cursor` | owner, OPERATOR | Read Model, keyset on (booked_at, entry_seq) |
| `GET /transactions/{id}` | OPERATOR | Write side |
| `POST /corrections` | OPERATOR | Idempotency-Key required. `{kind: REVERSING / ADJUSTMENT, transactionId?, reason, note, accountId?, amountPence?, direction?}`. `transactionId` is required for REVERSING and optional for ADJUSTMENT: a goodwill Adjustment corrects no Transaction, while one fixing a wrong amount names the Transaction it corrects. |
| `GET /dashboard` | OPERATOR | Read Model plus lag and balanced-check gauges |
| `POST /scheme/callbacks` | simulator (HMAC) | pacs.002-lite |
| `POST /demo-sessions` | public | `{role: CUSTOMER / OPERATOR}`; rate-limited |

A problem about one request field names it in a `field` extension member, by its path in the JSON body (such as `amountPence` or `payee.sortCode`), so a client can show it beside that field. Problem types include `validation-failed`, `idempotency-key-reused`, `idempotency-key-missing`, `already-reversed`, `payment-pending`, `seed-data-read-only`, `forbidden`, `not-found` and `rate-limited`.

## 8. Auth and public demo

- **Sessions:** Spring Security with an in-memory server-side session (a redeploy logs users out). Cookies are `SameSite=Lax`, because `ledger.<your-domain>` and `api.ledger.<your-domain>` are the same site. CSRF uses a cookie-to-header token. CORS allows only the SPA origin.
- **Roles:**
  - CUSTOMER sees and pays only from their own Accounts.
  - OPERATOR sees everything and posts Corrections, but makes no Payments.
- **No Keycloak or OAuth in v1.**
- **"Try as customer"** creates a Demo Sandbox: a new Customer with two Customer Accounts, funded from Funding by real Transactions.
- **"Try as operator"** can correct only Transactions on Demo Sandbox Accounts. Seeded data is read-only for everyone.
  - Seeded data means a `source=SEED` Transaction, or a seeded Customer Account: one owned by a Customer the seed loaded (`customer.seeded`). Internal Accounts are shared by seeded and live data, so they are never seeded.
  - Any Operator's Correction is refused with `409 /problems/seed-data-read-only` when it targets a `source=SEED` Transaction, or when every Customer Account it would touch is seeded: an Adjustment of a seeded Customer Account, or a Reversing Correction of a live Transaction whose Customer Accounts are all seeded (such as a load-test Payment).
  - This is the narrowest reading of the rule: a live Transaction between a seeded and a live Customer Account can still be corrected. The seed's own Corrections are bulk-loaded directly and never pass this check.
- **Abuse limits:**
  - one sandbox per IP per 10 minutes
  - 500 sandboxes per day globally
  - 30 POSTs per minute per session, enforced by an in-memory token bucket
  - Faster Payments only ever reach the simulator
- Sandboxes are never deleted.

## 9. Front end (v1 screens)

Built with MUI, including MUI X Data Grid, Charts and Date Pickers on their free tiers. Live views poll every 2 s through TanStack Query.

0. **Landing:** the claim, an architecture diagram, and buttons for "Try as customer" and "Try as operator". [M6]
1. **Accounts:** Balance, Available Balance and Overdraft Limit. [M5]
2. **Account detail:** the Statement, plus an As-At Balance picker. Shows "updating…" while Projection Freshness trails the write side. [M5]
3. **New Payment:** Internal Transfer or Faster Payment. Live status Pending → Completed or Rejected, with the reason. One Idempotency-Key per form submission. [M5]
4. **Operator dashboard:** payments per second, outcomes, Rejection Reasons, outbox lag, Projection Freshness, the balanced-check status with time of last run, and Stuck Payments. [M6]
5. **Transaction detail and Correction form.** The first cut if time runs short. [M6]

The M1 transfer page is bare and unstyled. Tests are Vitest for components plus one Playwright smoke test of the happy path.

## 10. Seed data

`seed/` is a CLI with a fixed random seed, so identical input gives identical output.

- **Volume:**
  - 5,000 Customers
  - about 6,500 Customer Accounts
  - about 1M Transactions, booked from 2025-11-01 to 2026-10-31 (12 monthly partitions)
  - 200 load-test Accounts with large Balances
- **Patterns:**
  - monthly salary-like funding from Funding
  - daily spending by Faster Payments to synthetic Payees
  - transfers between a Customer's own Accounts
  - Overdraft Limits on about 10% of Accounts
  - about 2% Scheme Rejections, each with its Reversal
  - a few hundred Corrections
- **Synthetic identities:** names from Datafaker with a fixed seed. Sort codes come from one range documented as fictional.
- **Built with the domain Transaction aggregate**, so every seeded Transaction balances. The generator computes `balance_after` and funds checks in memory, then bulk-loads Entries, Transactions and outbox rows with `COPY`. The publisher then drains the outbox into Kafka. This doubles as a throughput demo and is how the Read Model gets the seeded history.
- **Seed is the only path allowed to set a past `booked_at`.** Those rows are marked `source=SEED`.

## 11. Non-functional targets

| Target | Value |
|---|---|
| Sustained throughput (N) | ≥ 250 payments/s target; the measured value is published |
| `POST /payments` latency at N | p99 < 200 ms |
| Error rate at N | < 0.1% |
| Outbox lag at N | < 1 s |
| Projection Freshness at N | p99 < 2 s |
| Lost events | 0 |
| Balanced Ledger | always; any imbalance alerts |
| Availability | no SLA (demo); a few seconds of downtime per deploy accepted |
| Backups | none; data is synthetic and the deterministic re-seed is the recovery plan (a real bank would use PITR) |

**Memory budget** on the 4 GiB t4g.medium, to be checked in M6:

| Component | Budget |
|---|---|
| Postgres | 768 MB |
| Kafka (512 MB heap) | about 1 GB |
| ledger | 512 MB |
| projector | 320 MB |
| simulator | 256 MB |
| Alloy | 128 MB |
| Caddy | 30 MB |

## 12. Load test plan

- **Tool:** Gatling Java DSL in `loadtest/`, run from Maven.
- **Main mix:**

  | Share | Request |
  |---|---|
  | 70% | Internal Transfers between random load-test Accounts |
  | 15% | Faster Payments |
  | 5% | Retries with a reused Idempotency-Key |
  | 5% | Payments that fail for Insufficient Funds |
  | 5% | Balance and Statement reads |

- **Hot-account run:** 100% Faster Payments, to measure contention on FPS Clearing (ADR 0004).
- **N** = the highest payment rate held for 10 minutes with p99 < 200 ms, errors < 0.1% and outbox lag < 1 s.
- **Where it runs:** a short-lived c7g.medium load generator in eu-west-2, switched on by a Terraform toggle. Never on the app box, never over home broadband.
- **Caveat stated in the README:** t4g is burstable, so tests run in "unlimited" CPU-credit mode.
- **Published:**
  - a README table with commit SHA, instance types and versions
  - the Gatling HTML report in `docs/loadtest/<YYYY-MM-DD>/`
  - a Grafana screenshot

## 13. Observability

- **Stack:** Grafana Cloud free tier (10k active series; 50 GB of logs and 50 GB of traces; 14-day retention). A Grafana Alloy agent on the box scrapes Micrometer/Prometheus endpoints and ships logs and OTLP traces. Histogram buckets are capped to stay under 10k series. A public dashboard link goes in the README.
- **Metrics:** payments/s by type and outcome; `POST /payments` latency (p50/p95/p99); outbox unpublished count and oldest age; Kafka consumer lag; Projection Freshness; Stuck Payments; scheme anomalies; lock wait time; `ledger_balanced`; `ledger_imbalance_pence`; missing next-month partition.
- **Tracing:** OpenTelemetry through Spring Boot 4's OTLP support. The `traceparent` is carried through the outbox row and the Kafka header, so one trace covers HTTP → posting → outbox → Kafka → projector.
- **Logs:** Spring Boot structured logging in ECS JSON, with `traceId`, `paymentId` and `transactionId` in the MDC.
- **Balanced check**, every 60 s:
  - (a) Σdebits = Σcredits across the whole ledger
  - (b) for Accounts touched since the last run, the stored Balance = the Σ of its Entries
  - (c) for the same Accounts, the Read Model Balance = the write-side Balance

  A full sweep of (b) runs nightly. Results are exposed as gauges, alerted on and shown on the Operator dashboard.

## 14. Postgres

- **Version:** Postgres 18, with `uuidv7()` for public ids and `bigint` identity for Entries.
- **Partitioning:** Entries are range-partitioned by month on `booked_at`.
  - Flyway creates the initial partitions.
  - A scheduled ledger job creates partitions 3 months ahead, using `CREATE TABLE IF NOT EXISTS … PARTITION OF`.
  - There is no DEFAULT partition and no pg_partman.
  - Partitions are never dropped.
- **Indexes:** `(account_id, booked_at DESC, entry_seq DESC) INCLUDE (balance_after_pence)` on each partition, for an ordered, index-only As-At lookup.
- **Vacuum:** `autovacuum_vacuum_insert_scale_factor` is tuned so insert-only vacuum keeps the visibility map current.
- **ADR 0010:** records EXPLAIN (ANALYZE, BUFFERS) before (an unpartitioned table with its PK only) and after (partitioned and indexed), at 1M+ Transactions, for three queries: As-At Balance, a Statement page and the write path.

## 15. CI/CD and hosting

- **CI on every PR:** Maven build; unit tests; Testcontainers integration tests (real Postgres 18 and real Kafka, never H2); the Spring Modulith `verify()` test; for `web`, type check, lint and Vitest; the Playwright smoke test against Compose (from M6).
- **On `main`:** Jib builds arm64 images for the three Java services and pushes them to GHCR. The web app deploys to Cloudflare Pages through wrangler.
- **Deploy** on a `v*` tag: GitHub OIDC assumes an IAM role, then SSM Run Command runs `docker compose pull && docker compose up -d`. Only ports 80 and 443 are open: no SSH and no long-lived AWS keys.
- **Terraform:**
  - state in S3 with native S3 locking
  - resources: default VPC, EC2 t4g.medium, Elastic IP, security group, IAM instance profile and OIDC role, two AWS Budgets, and the load-generator toggle
  - `plan` runs on PRs that touch `infra/`; `apply` is a manual `workflow_dispatch` with environment approval
- **Secrets:** app secrets (DB password, HMAC key) live in SSM Parameter Store SecureString and are read at boot. The Cloudflare API token is the only GitHub secret.
- **Migrations:** Flyway runs on startup. The ledger migrates the `ledger` database and the projector migrates `readmodel`.
- **Hosting:**
  - one EC2 t4g.medium in eu-west-2 running Docker Compose (ledger, simulator, projector, Postgres, Kafka, Caddy, Alloy)
  - Caddy terminates TLS for `api.ledger.<your-domain>`
  - the SPA is at `ledger.<your-domain>` on Cloudflare Pages
- **AWS account:**
  - It is opened only on deployment day in M6. Credits expire 12 months after opening.
  - On day one, complete the five credit activities: EC2 launch, Budgets, a Lambda function URL, a DynamoDB table and one Bedrock playground prompt.
  - Two Budgets alerts: net cost including credits at $1, and a gross cost forecast at $35/month.
  - Known risk: UK VAT may not be covered by credits.
- **Fallback:** when the credits run out (after about 6 months), the same Compose file moves to an Oracle Cloud Always Free Arm VM. See ADR 0011.

## 16. Git conventions

- **Branching:** trunk-based, with one short-lived branch per vertical slice, merged by PR once CI is green.
- **Merging:** rebase-merge, not squash, so the red → green → refactor commits stay visible.
- **Commits:** Conventional Commits, pushed at least daily.
- **Tags:** `v0.1.0` … `v0.6.0` at each milestone; `v1.0.0` = shipped.
- **Visibility:** the repo stays private until M1 is green, and goes public only with the owner's explicit yes.

## 17. v2 backlog (only after v1 ships)

Each item must add something a recruiter can see.

- **5:** card holds and settlement (available vs ledger balance, hold expiry, tips)
- **8:** end-of-day reconciliation with an Exceptions dashboard
- multi-currency with FX conversion
- Direct Debits
- interest accrual, which brings value dates
- monthly PDF statements
- maker-checker (four-eyes approval) for Corrections
- sharded sub-accounts for the FPS Clearing hot account

## 18. Explicitly out of v1

- Kubernetes, gRPC, microservices for show
- managed Kafka (MSK), RDS, ECS
- Debezium, Schema Registry/Avro, Spring Modulith's event publication registry
- JPA/Hibernate
- Keycloak/OAuth
- inbound payments; Confirmation of Payee; settlement with the Bank of England
- value dates; changing Overdraft Limits through the API
- WebSockets/SSE
- backups; multi-region
- Next.js, Remix, Astro, TanStack Start
