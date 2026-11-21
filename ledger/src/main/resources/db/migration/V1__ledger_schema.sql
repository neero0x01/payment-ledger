-- The walking-skeleton ledger schema. Amounts are GBP pence; Entries carry a positive amount
-- and a side (ADR 0003).

create table customer (
    id           uuid primary key default uuidv7(),
    display_name text not null,
    created_at   timestamptz not null default clock_timestamp()
);

create table account (
    id          uuid primary key default uuidv7(),
    kind        text not null check (kind in ('CUSTOMER', 'INTERNAL')),
    customer_id uuid references customer (id),
    name        text not null,
    normal_side text not null check (normal_side in ('DEBIT', 'CREDIT')),
    created_at  timestamptz not null default clock_timestamp(),
    check ((kind = 'CUSTOMER') = (customer_id is not null))
);

create unique index account_internal_name_uq on account (name) where kind = 'INTERNAL';

insert into account (kind, name, normal_side) values ('INTERNAL', 'Funding', 'DEBIT');

-- Cached Balance on the Account's Normal Side; a derivation of the Entries, never a source of truth.
create table account_balance (
    account_id    uuid primary key references account (id),
    balance_pence bigint not null,
    updated_at    timestamptz not null default clock_timestamp()
);

create table payment (
    id                uuid primary key default uuidv7(),
    type              text not null check (type in ('INTERNAL_TRANSFER')),
    debtor_account_id uuid not null references account (id),
    payee_account_id  uuid not null references account (id),
    amount_pence      bigint not null check (amount_pence > 0),
    status            text not null check (status in ('PENDING', 'COMPLETED')),
    created_at        timestamptz not null default clock_timestamp(),
    completed_at      timestamptz
);

create table ledger_transaction (
    id         uuid primary key default uuidv7(),
    kind       text not null check (kind in ('PAYMENT', 'FUNDING')),
    booked_at  timestamptz not null,
    payment_id uuid references payment (id),
    check ((kind = 'PAYMENT') = (payment_id is not null))
);

create index ledger_transaction_payment_idx on ledger_transaction (payment_id);

create table entry (
    id             bigint generated always as identity primary key,
    transaction_id uuid not null references ledger_transaction (id),
    account_id     uuid not null references account (id),
    side           text not null check (side in ('DEBIT', 'CREDIT')),
    amount_pence   bigint not null check (amount_pence > 0),
    booked_at      timestamptz not null
);

create index entry_transaction_idx on entry (transaction_id);
