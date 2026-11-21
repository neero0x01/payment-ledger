# No ORM: plain SQL through Spring JdbcClient

JPA/Hibernate is the default in most Spring shops, and the ledger deliberately doesn't use it. Every database access goes through Spring's `JdbcClient` with hand-written SQL.

The ledger's write path is append-only inserts plus one locked balance-row read, so JPA's strengths go unused: object graphs, dirty checking and lazy loading. Dirty checking is even a hazard here, because a stray setter call turns into an `UPDATE` on a row that must never change.

Writing the SQL by hand gives three things:

- the exact SQL that runs is the SQL we EXPLAIN and tune (ADR 0010)
- explicit `SELECT … FOR UPDATE` ordering (ADR 0004) and `COPY`-based seeding
- a domain model free of persistence annotations

## Considered options

- **Spring Data JPA:** familiar, and it *can* lock (`@Lock(PESSIMISTIC_WRITE)`) and work with partitioned tables, but you end up tuning SQL that Hibernate generated.
- **jOOQ:** type-safe SQL, but adds a code-generation step and a commercial licence for some databases.
- **Hybrid (JPA for accounts and payments, SQL for posting):** realistic in banks, but it means two persistence styles in a small codebase.

## Consequences

Row mapping and repetitive SQL are written by hand and covered by Testcontainers integration tests. Nothing guards against a mistyped column except those tests.
