# A modular monolith plus exactly two extracted services

The ledger core (`accounts`, `payments`, `posting`, `outbox`, `views`, and `demo` since ADR 0012) is one Spring Boot application. Spring Modulith verifies its module boundaries in CI. Two parts run as separate services, each for a concrete reason:

- **`fps-simulator`** stands in for an external party, the Faster Payments Scheme, so it must sit across a network boundary.
- **`projector`** demonstrates CQRS: it builds the Read Model from Kafka and can be rebuilt or lag without affecting Payments.

All of it lives in one monorepo with a single CI pipeline, so recruiters have one link.

## Considered options

- **Microservices per module:** rejected as "microservices for show". They would bring distributed transactions into a domain whose core guarantee depends on one ACID transaction.
- **Everything in one process:** hides both the external scheme boundary and the CQRS boundary.
