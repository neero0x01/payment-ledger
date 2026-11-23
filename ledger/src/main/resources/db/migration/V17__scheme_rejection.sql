-- A Scheme Rejection makes a Faster Payment Rejected with reason SCHEME_REJECTED and stores the
-- scheme's ISO 20022 reason code. The ledger then posts a Reversal on its own: a Transaction of the
-- same Payment that mirrors the original and references it (spec Scenario 6). Like a Reversing
-- Correction, it counts against the partial unique index, so a Transaction is reversed at most once.

alter table payment drop constraint payment_rejection_reason_check;

alter table payment
    add constraint payment_rejection_reason_check
        check (rejection_reason in ('INSUFFICIENT_FUNDS', 'SCHEME_REJECTED'));

alter table payment
    add column scheme_reason_code text check (scheme_reason_code in ('AC01', 'AC04', 'AG01', 'MS03')),
    add constraint payment_scheme_rejected_has_reason_code_check
        check ((rejection_reason = 'SCHEME_REJECTED') = (scheme_reason_code is not null));

alter table ledger_transaction drop constraint ledger_transaction_kind_check;

alter table ledger_transaction
    add constraint ledger_transaction_kind_check
        check (kind in ('PAYMENT', 'REVERSAL', 'FUNDING', 'CORRECTION'));

-- A Payment Transaction and a Reversal belong to a Payment; no other Transaction does.
alter table ledger_transaction drop constraint ledger_transaction_check;

alter table ledger_transaction
    add constraint ledger_transaction_payment_check
        check ((kind in ('PAYMENT', 'REVERSAL')) = (payment_id is not null)),
    add constraint ledger_transaction_reversal_check
        check (kind <> 'REVERSAL' or reverses_transaction_id is not null);
