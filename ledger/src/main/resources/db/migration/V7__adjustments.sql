-- Adjustments: Corrections that move an amount between a Customer Account and the Corrections
-- Account. Every Correction, Reversing Corrections included, carries a Correction Reason and a
-- note, and no other Transaction does.

insert into account (kind, name, normal_side) values ('INTERNAL', 'Corrections', 'DEBIT');

alter table ledger_transaction
    add column correction_reason text
        check (correction_reason in
               ('DUPLICATE_POSTING', 'WRONG_AMOUNT', 'WRONG_ACCOUNT', 'GOODWILL', 'OTHER')),
    add column correction_note text,
    add constraint ledger_transaction_correction_details_check
        check ((kind = 'CORRECTION') = (correction_reason is not null and correction_note is not null));
