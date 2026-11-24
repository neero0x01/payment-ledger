-- A Faster Payment still Pending when its Scheme Outcome deadline has passed is flagged as a Stuck
-- Payment (spec Scenario 6, ADR 0008). It stays Pending and is never reversed automatically: the
-- money may already have left, so an Operator looks at it.

alter table payment
    add column stuck boolean not null default false;
