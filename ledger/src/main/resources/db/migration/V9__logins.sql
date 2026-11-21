-- Logins: how Customers and Operators prove who they are before the ledger opens a session for
-- them (spec §8). A Customer's login belongs to that Customer; an Operator's belongs to no
-- Customer. Passwords are stored only as hashes. Append-only for ledger_app (V8).

create table login (
    id            uuid primary key default uuidv7(),
    username      text not null unique,
    password_hash text not null,
    role          text not null check (role in ('CUSTOMER', 'OPERATOR')),
    customer_id   uuid references customer (id),
    created_at    timestamptz not null default clock_timestamp(),
    check ((role = 'CUSTOMER') = (customer_id is not null))
);
