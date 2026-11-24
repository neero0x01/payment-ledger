---
status: accepted
---

# The ledger talks to the payment scheme over HTTP with signed callbacks, not Kafka

**Status: Accepted** in M5. The ledger's `PaymentScheme` HTTP adapter posts each Scheme Submission to the `fps-simulator` service, which answers `202`, dedupes on End-to-End Id against its own `fpssim` database and later sends an HMAC-SHA256-signed Scheme Outcome to `POST /v1/scheme/callbacks`. The end-to-end test runs the ledger against the real simulator container: an accepted Faster Payment is Completed, and one to a Payee account ending `0401` is Rejected with `AC04` and reversed.

Faster Payments go to the simulator as a slimmed-down ISO 20022 pacs.008 over HTTP. The simulator returns `202` and later calls back with a pacs.002 (`ACSC`, or `RJCT` with a reason code), signed with HMAC.

A real bank doesn't share a Kafka cluster with a payment scheme, so Kafka stays inside the fintech, used for its real job of internal event distribution.

The call is never made inside the posting transaction. A dispatcher picks up Pending Faster Payments after commit, which is the outbox pattern applied to commands. It re-sends after 15 s, and the simulator dedupes on End-to-End Id. A Payment with no outcome after 5 min is flagged as a Stuck Payment and is **never reversed automatically**, because the money may already have left. It is still re-sent with the same End-to-End Id, once a minute, so a scheme outage longer than 5 min ends with its outcome.

The scheme sits behind a `PaymentScheme` port, with an HTTP adapter in production and an in-memory fake in unit tests.
