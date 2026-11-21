-- Reversing Corrections: a Correction Transaction that mirrors one whole earlier Transaction and
-- is linked to it. A Transaction can be reversed at most once (spec §2, rule 8), which the partial
-- unique index guarantees even when two reversals race.

alter table ledger_transaction drop constraint ledger_transaction_kind_check;
alter table ledger_transaction
    add constraint ledger_transaction_kind_check check (kind in ('PAYMENT', 'FUNDING', 'CORRECTION'));

alter table ledger_transaction
    add column reverses_transaction_id uuid references ledger_transaction (id);

create unique index ledger_transaction_reverses_uq
    on ledger_transaction (reverses_transaction_id)
    where reverses_transaction_id is not null;
