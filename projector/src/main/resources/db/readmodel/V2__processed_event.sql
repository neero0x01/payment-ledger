-- Delivery is at least once (ADR 0005). The projector records each event's eventId here in the same
-- database transaction as the changes it makes, and skips an event whose eventId is already here.
create table processed_event (
    event_id uuid primary key,
    processed_at timestamptz not null default clock_timestamp()
);
