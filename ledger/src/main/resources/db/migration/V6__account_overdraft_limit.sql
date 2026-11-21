-- An Overdraft Limit lets a Customer Account pay out beyond its Balance. Set by seed data only in
-- v1; Internal Accounts have no funds check, so theirs stays zero.

alter table account
    add column overdraft_limit_pence bigint not null default 0 check (overdraft_limit_pence >= 0);
