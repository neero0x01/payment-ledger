-- The transactional outbox (ADR 0005). Posting appends a row in the same database transaction as
-- the Transaction it describes; the polling publisher locks unpublished rows with SKIP LOCKED,
-- sends them to Kafka and sets published_at. payload is the whole Kafka message value: the event
-- envelope of ADR 0006.

create table outbox (
    id bigint generated always as identity primary key,
    event_id uuid not null unique,
    topic text not null,
    msg_key text not null,
    payload jsonb not null,
    created_at timestamptz not null default clock_timestamp(),
    published_at timestamptz
);

-- The publisher's poll and the lag gauge read only unpublished rows.
create index outbox_unpublished_idx on outbox (id) where published_at is null;

-- Outbox rows are mutable: the publisher marks them published (V8 makes new tables append-only).
grant update on outbox to ledger_app;
