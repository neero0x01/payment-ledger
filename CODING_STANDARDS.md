# Coding standards

These are judgement calls for code review. Formatting, type checks and bans that a tool can enforce live in the build (Spotless, Error Prone, ArchUnit, Spring Modulith `verify()`, `tsc --strict`, ESLint, Prettier), not here. Every rule says why, so edge cases can be judged.

## General

1. **Use the glossary's words.** Name types, methods, tests, endpoints and UI copy with the terms in `GLOSSARY.md`, and never with the synonyms it lists under _Avoid_. _Why:_ Payment vs Transaction and Reversal vs Correction are different things here.
2. **Name tests after behaviour.** Name a test for the scenario it proves (`rejects_payment_beyond_available_balance`), not the method it calls. _Why:_ the test list should read like the spec.
3. **Test at the agreed seams.** Drive behaviour through the ledger HTTP API, the `PaymentScheme` port or the Kafka event contracts, and assert only on outcomes you can observe. The only exception is property tests on `Money` and the Transaction aggregate. _Why:_ tests that survive refactors.
4. **No speculative options.** Don't add a flag, parameter or extension point that no ticket asks for. _Why:_ scope is set by the spec and tickets.
5. **Flag ADR conflicts in the PR.** If a change goes against an ADR, say so in the PR and propose a new ADR. Never work around the old one silently.

## Java

6. **Map rows by hand in adapters.** Row mapping and SQL live in each module's `internal` package; ArchUnit keeps the domain types outside it plain Java. _Why:_ ADR 0001.
7. **Give domain values their own types.** Use `Money` (never `long` pence or `BigDecimal` in signatures) and id types such as `AccountId` and `PaymentId` (never a bare `UUID`). _Why:_ swapped arguments become compile errors.
8. **Never change Entries or Transactions.** Fix mistakes with a new Transaction (a Reversal or a Correction); application code never updates or deletes these rows. A migration may backfill a new column on existing rows once, and says so in a comment. _Why:_ the core invariant. The database role also enforces it.
9. **Keep each money operation in one database transaction.** The Payment insert, balance locks, Entries, balance update and outbox rows commit together, so never call an external system in the middle. _Why:_ ADRs 0004, 0005 and 0007. No dual writes.
10. **Lock balance rows in ascending account id order,** one at a time, and read Booking Time only after the locks are held. _Why:_ ADR 0004, which rules out deadlocks and keeps `entry_seq` in the same order as Booking Time.
11. **Write SQL by hand and keep it readable.** One statement per method, a text block when it spans several lines, named parameters, and no SQL assembled by string concatenation. _Why:_ the SQL that ships is the SQL we EXPLAIN.
12. **Put business outcomes in the result.** Insufficient Funds or Scheme Rejected is a Rejected Payment, not an exception. Use exceptions for broken requests and broken invariants. _Why:_ rejections are stored and replayed (ADR 0007).
13. **Return API errors as RFC 9457 problem details** with a stable `type` URI from the shared catalogue. Don't send ad-hoc error bodies. _Why:_ clients and tests branch on `type`.
14. **Talk to other modules only through their public API.** No reaching into another module's internals or tables, even when Modulith can't see it. For example, `views` reads the `readmodel` database, and other modules only through their public APIs (ADR 0012). _Why:_ ADR 0002.

## TypeScript / React

15. **Keep server state in TanStack Query.** Never copy API data into `useState` or a store. _Why:_ one source of truth, and polling just works.
16. **Make an Idempotency-Key once per user submission,** and reuse it on every retry of that submission. _Why:_ a double-click must never create two Payments.
17. **Validate URL state through the router.** Search parameters (date ranges, `asAt`, cursors) go through TanStack Router's validation. Don't parse them by hand in components. _Why:_ typed, shareable URLs.
18. **Show amounts as GBP from pence,** using one shared formatter, and never do arithmetic on floats. _Why:_ pence are integers end to end.
19. **Show staleness honestly.** Anything read from the Read Model shows "updating…" while it trails a Payment the user just made. Never claim a view is fresher than it is. _Why:_ CQRS is visible on purpose.
20. **Use MUI components and the theme.** No one-off inline styles or hard-coded colours. _Why:_ consistency at minimal cost.
