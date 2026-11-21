-- A Payment can be Rejected with a Rejection Reason, and the Rejected Payment is stored (ADR 0007).

alter table payment drop constraint payment_status_check;

alter table payment
    add constraint payment_status_check check (status in ('PENDING', 'COMPLETED', 'REJECTED'));

alter table payment
    add column rejection_reason text check (rejection_reason in ('INSUFFICIENT_FUNDS'));

alter table payment
    add constraint payment_rejected_has_reason_check
        check ((status = 'REJECTED') = (rejection_reason is not null));
