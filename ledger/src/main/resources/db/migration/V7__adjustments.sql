-- Adjustments: Corrections that move an amount between a Customer Account and the Corrections
-- Account. Every Correction carries a Correction Reason and a note (spec §7).

insert into account (kind, name, normal_side) values ('INTERNAL', 'Corrections', 'DEBIT');

alter table ledger_transaction
    add column correction_reason text
        check (correction_reason in
               ('DUPLICATE_POSTING', 'WRONG_AMOUNT', 'WRONG_ACCOUNT', 'GOODWILL', 'OTHER')),
    add column correction_note text;
