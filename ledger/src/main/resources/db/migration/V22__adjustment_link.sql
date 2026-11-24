-- An Adjustment that fixes a mistake names the Transaction it corrects (spec §7); a goodwill
-- Adjustment names none. A Reversing Correction keeps its link in reverses_transaction_id, so only
-- an Adjustment has this one. Existing rows are left as they are (CODING_STANDARDS rule 8).

alter table ledger_transaction
    add column adjusts_transaction_id uuid references ledger_transaction (id),
    add constraint ledger_transaction_adjusts_check
        check (adjusts_transaction_id is null
               or (kind = 'CORRECTION' and reverses_transaction_id is null));

-- The Transaction detail lists every Correction of a Transaction.
create index ledger_transaction_adjusts_idx
    on ledger_transaction (adjusts_transaction_id)
    where adjusts_transaction_id is not null;
