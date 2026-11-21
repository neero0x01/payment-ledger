# Payment Ledger

A double-entry payment ledger for a fictional small UK fintech, built in Java/Spring Boot test-first:

- Entries are append-only.
- Every Transaction balances to zero.
- Balances are derived, never edited.
- Outbound Faster Payments go through a simulated scheme.
- A Read Model, fed by Kafka through a transactional outbox, powers the dashboards.

> Built a double-entry payment ledger in Java/Spring Boot that never double-charges, never loses an event, never applies one twice and can prove every balance, load-tested at **N** payments a second, deployed on AWS.

## Status

**Specified, not yet built** (as of 2026-11-21). Next up: milestone M1, the walking skeleton.

| | |
|---|---|
| Live demo | _not deployed yet_ |
| Load test (N payments/s, p99) | _not run yet_ |
| Grafana dashboard | _not published yet_ |

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
