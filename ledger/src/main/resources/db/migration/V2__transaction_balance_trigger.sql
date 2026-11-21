-- The database's own guard on the double-entry rule, behind the Transaction aggregate: at commit,
-- every Transaction touched has at least two Entries and its Debits total equals its Credits total.
-- Deferred, so a Transaction and its Entries can be inserted one row at a time.

create function check_transaction_balanced() returns trigger
language plpgsql as $$
declare
    checked_id  uuid;
    entry_count bigint;
    debits      numeric;
    credits     numeric;
begin
    if tg_table_name = 'entry' then
        checked_id := new.transaction_id;
    else
        checked_id := new.id;
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

create constraint trigger ledger_transaction_balanced
    after insert on ledger_transaction
    deferrable initially deferred
    for each row execute function check_transaction_balanced();

create constraint trigger entry_transaction_balanced
    after insert on entry
    deferrable initially deferred
    for each row execute function check_transaction_balanced();
