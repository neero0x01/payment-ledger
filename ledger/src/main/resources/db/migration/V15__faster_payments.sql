-- Faster Payments: a Payment to a sort code, account number and name at another UK bank, sent
-- through the Faster Payments Scheme (spec Scenario 6, ADR 0008). Its money waits in FPS Clearing,
-- the debit-normal Clearing Account. Each Faster Payment carries the End-to-End Id that travels
-- with it through the scheme.

insert into account (kind, name, normal_side) values ('INTERNAL', 'FPS Clearing', 'DEBIT');

alter table payment drop constraint payment_type_check;

alter table payment
    add constraint payment_type_check check (type in ('INTERNAL_TRANSFER', 'FASTER_PAYMENT'));

alter table payment
    alter column payee_account_id drop not null,
    add column payee_sort_code      text check (payee_sort_code ~ '^[0-9]{6}$'),
    add column payee_account_number text check (payee_account_number ~ '^[0-9]{8}$'),
    add column payee_name           text check (length(trim(payee_name)) > 0),
    add column end_to_end_id        text unique;

-- An Internal Transfer pays an Account at the fintech; a Faster Payment pays an external sort code,
-- account number and name, and has an End-to-End Id.
alter table payment
    add constraint payment_payee_check check (
        (type = 'INTERNAL_TRANSFER'
            and payee_account_id is not null
            and payee_sort_code is null and payee_account_number is null and payee_name is null
            and end_to_end_id is null)
        or (type = 'FASTER_PAYMENT'
            and payee_account_id is null
            and payee_sort_code is not null and payee_account_number is not null
            and payee_name is not null
            and end_to_end_id is not null));
