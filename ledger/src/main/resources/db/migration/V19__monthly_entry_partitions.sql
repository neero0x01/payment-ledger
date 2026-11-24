-- Entries are range-partitioned by month on booked_at (ADR 0010, spec §10). This rebuilds entry as
-- a partitioned table and moves every existing Entry into it, once.
--
-- A partition holds one calendar month in UTC and is named entry_YYYY_MM. There is no DEFAULT
-- partition: an Entry booked in a month without a partition is refused, because rows parked in a
-- DEFAULT partition would block creating that month's partition later. Partitions are never
-- dropped. Flyway creates every month from the seed's first, November 2025 (or the month of the
-- oldest existing Entry, if earlier), through three months ahead; the ledger's partition
-- maintenance job keeps creating them three months ahead from then on.
--
-- Unique constraints on a partitioned table must include the partition key, so the primary key is
-- (id, booked_at), and (account_id, entry_seq) cannot be unique in the database. The ordered
-- balance-row lock guarantees it instead (ADR 0004).

-- Creates the partition for the month that starts on the date given, unless it exists. It runs as
-- the schema owner, which owns entry, so ledger_app can create partitions without owning tables.
--
-- Entries are insert-only, so only insert-driven autovacuum sets their pages all-visible, which
-- the As-At index-only scan needs. The default vacuums after inserts of 20% of the partition's
-- size, which lets a growing month's visibility map fall further behind the bigger it gets; each
-- partition is vacuumed after every 10,000 inserts instead. Postgres can't set this on the
-- partitioned table itself, so it is set on each partition.
create function create_entry_partition(month date) returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    first_day date := date_trunc('month', month)::date;
begin
    execute format(
        'create table if not exists %I partition of entry for values from (%L) to (%L)
         with (autovacuum_vacuum_insert_scale_factor = 0, autovacuum_vacuum_insert_threshold = 10000)',
        'entry_' || to_char(first_day, 'YYYY_MM'),
        first_day::timestamp at time zone 'UTC',
        (first_day + interval '1 month')::timestamp at time zone 'UTC');
end;
$$;

revoke execute on function create_entry_partition(date) from public;
grant execute on function create_entry_partition(date) to ledger_app;

alter table entry rename to entry_unpartitioned;
alter index entry_pkey rename to entry_unpartitioned_pkey;
alter index entry_transaction_idx rename to entry_unpartitioned_transaction_idx;
drop trigger entry_transaction_balanced on entry_unpartitioned;
-- Frees the name entry_id_seq for the new table's identity.
alter table entry_unpartitioned alter column id drop identity;

create table entry (
    id                  bigint generated always as identity,
    transaction_id      uuid not null references ledger_transaction (id),
    account_id          uuid not null references account (id),
    side                text not null check (side in ('DEBIT', 'CREDIT')),
    amount_pence        bigint not null check (amount_pence > 0),
    booked_at           timestamptz not null,
    entry_seq           bigint not null constraint entry_entry_seq_positive check (entry_seq > 0),
    balance_after_pence bigint not null,
    primary key (id, booked_at)
) partition by range (booked_at);

create index entry_transaction_idx on entry (transaction_id);

-- The As-At Balance lookup: the newest Entry of an Account at or before a moment, read from the
-- index alone, newest partition first, stopping at the first match.
create index entry_account_as_at_idx
    on entry (account_id, booked_at desc, entry_seq desc) include (balance_after_pence);

select create_entry_partition(month::date)
from generate_series(
         least(
             date '2025-11-01',
             (select date_trunc('month', min(booked_at) at time zone 'UTC') from entry_unpartitioned)),
         date_trunc('month', now() at time zone 'UTC') + interval '3 months',
         interval '1 month') as month;

-- The Entries were checked when their Transactions committed, so they move without the trigger.
insert into entry (
    id, transaction_id, account_id, side, amount_pence, booked_at, entry_seq, balance_after_pence)
overriding system value
select id, transaction_id, account_id, side, amount_pence, booked_at, entry_seq, balance_after_pence
from entry_unpartitioned;

select setval(
    pg_get_serial_sequence('entry', 'id'),
    coalesce((select max(id) from entry), 0) + 1,
    false);

drop table entry_unpartitioned;

-- A row trigger on a partitioned table fires with the partition's name in tg_table_name, so the
-- double-entry guard (V2) tells its two tables apart by the Transaction's table instead.
create or replace function check_transaction_balanced() returns trigger
language plpgsql as $$
declare
    checked_id  uuid;
    entry_count bigint;
    debits      numeric;
    credits     numeric;
begin
    if tg_table_name = 'ledger_transaction' then
        checked_id := new.id;
    else
        checked_id := new.transaction_id;
    end if;

    select count(*),
           coalesce(sum(amount_pence) filter (where side = 'DEBIT'), 0),
           coalesce(sum(amount_pence) filter (where side = 'CREDIT'), 0)
    into entry_count, debits, credits
    from entry
    where transaction_id = checked_id;

    if entry_count < 2 then
        raise exception 'Transaction % has % Entries; it needs at least two', checked_id, entry_count
            using errcode = 'integrity_constraint_violation';
    end if;
    if debits <> credits then
        raise exception 'Transaction % does not balance: Debits % and Credits %', checked_id, debits, credits
            using errcode = 'integrity_constraint_violation';
    end if;
    return null;
end;
$$;

create constraint trigger entry_transaction_balanced
    after insert on entry
    deferrable initially deferred
    for each row execute function check_transaction_balanced();

-- Append-only for the application, as before (V3).
grant select, insert on entry to ledger_app;
