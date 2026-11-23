-- The Read Model (spec §6): ledger data reshaped for viewing, built only by the projector from the
-- ledger's Kafka events. The ledger's views module reads it as readmodel_reader.

-- An Account's Balance as of the Entry with the highest entry_seq applied to it.
create table account_balance_view (
    account_id uuid primary key,
    balance_pence bigint not null,
    last_entry_seq bigint not null,
    updated_at timestamptz not null default clock_timestamp()
);

-- One Entry as the Customer sees it. counterparty_account_id is the Account on the other side of a
-- two-Entry Transaction, and null for a Transaction with more Entries.
create table statement_line (
    account_id uuid not null,
    entry_seq bigint not null,
    transaction_id uuid not null,
    booked_at timestamptz not null,
    side text not null check (side in ('DEBIT', 'CREDIT')),
    amount_pence bigint not null check (amount_pence > 0),
    balance_after_pence bigint not null,
    counterparty_account_id uuid
);

-- Statements are read newest first, a keyset page at a time.
create index statement_line_keyset_idx on statement_line (account_id, booked_at, entry_seq);

-- The views module's read-only role. Each of its connections runs `set role readmodel_reader`.
do $$
begin
    if not exists (select from pg_roles where rolname = 'readmodel_reader') then
        create role readmodel_reader nologin;
    end if;
end;
$$;

-- Lets a schema owner that is not a superuser switch to the role.
grant readmodel_reader to current_user;

grant usage on schema public to readmodel_reader;
grant select on all tables in schema public to readmodel_reader;
alter default privileges in schema public grant select on tables to readmodel_reader;
