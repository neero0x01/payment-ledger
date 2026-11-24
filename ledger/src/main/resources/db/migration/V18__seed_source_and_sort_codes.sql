-- Seed data (spec §10). Every Transaction records where it came from: LIVE when the running ledger
-- posted it, SEED when the seed generator bulk-loaded it. The seed is the only path allowed to set
-- a past booked_at. The column's default backfills every existing Transaction as LIVE, once.
--
-- A Customer Account is known to other banks by its sort code and account number. Seeded Accounts
-- have them, at sort codes from the range the seed documents as fictional; Accounts opened before
-- this migration have none.

alter table ledger_transaction
    add column source text not null default 'LIVE' check (source in ('LIVE', 'SEED'));

alter table account
    add column sort_code      text check (sort_code ~ '^[0-9]{6}$'),
    add column account_number text check (account_number ~ '^[0-9]{8}$'),
    add constraint account_sort_code_and_number_check
        check ((sort_code is null) = (account_number is null));

create unique index account_sort_code_account_number_uq
    on account (sort_code, account_number)
    where sort_code is not null;
