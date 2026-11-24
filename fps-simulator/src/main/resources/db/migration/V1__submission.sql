-- Every Scheme Submission the simulator has decided, keyed on its End-to-End Id so a re-sent
-- submission gets the stored outcome (spec §6). A black-holed submission never gets an outcome:
-- its outcome and reason code stay null.
create table submission (
    end_to_end_id    text primary key,
    received_at      timestamptz not null default now(),
    black_holed      boolean     not null,
    outcome          text check (outcome in ('ACSC', 'RJCT')),
    reason_code      text check (reason_code in ('AC01', 'AC04', 'AG01', 'MS03')),
    callback_sent_at timestamptz,
    check (black_holed = (outcome is null)),
    check ((outcome = 'RJCT') = (reason_code is not null))
);
