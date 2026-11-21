# JSON events with JSON Schema in the repo, no Schema Registry

Events are JSON in a shared envelope (`eventId`, `type`, `schemaVersion`, `occurredAt`, `payload`). JSON Schema files live in `docs/schemas/`, and contract tests on both the producer and consumer sides check against them. A breaking change means a new topic (`.v2`).

One team owns both ends, and the box has 4 GiB of RAM. Avro with a Schema Registry would add a container and a serialization toolchain for a benefit (enforcing compatibility across teams) that this project doesn't need.

## Consequences

Compatibility is enforced by tests, not by a broker-side registry. Payloads are larger than Avro.
