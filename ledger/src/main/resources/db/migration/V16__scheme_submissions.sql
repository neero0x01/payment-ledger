-- The scheme dispatcher sends each Pending Faster Payment to the Faster Payments Scheme after it
-- commits (ADR 0008). A Payment records how many Scheme Submissions it has had and when the last
-- one was sent.

alter table payment
    add column submission_attempts integer not null default 0 check (submission_attempts >= 0),
    add column last_submitted_at   timestamptz;

create index payment_pending_faster_payment_idx
    on payment (created_at)
    where type = 'FASTER_PAYMENT' and status = 'PENDING';
