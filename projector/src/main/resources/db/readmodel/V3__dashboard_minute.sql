-- The Operator dashboard's figures (spec §6): how many Payments took each status, and their value,
-- per minute, by Payment type and Rejection Reason. Each PaymentStatusChanged event adds one Payment
-- to the minute it happened in. A Payment is made when it first takes a status: Pending for a
-- Faster Payment accepted for sending, otherwise its final status in the same request.
create table dashboard_minute (
    minute timestamptz not null,
    payment_type text not null check (payment_type in ('INTERNAL_TRANSFER', 'FASTER_PAYMENT')),
    status text not null check (status in ('PENDING', 'COMPLETED', 'REJECTED')),
    -- '' unless the status is REJECTED, so it can be part of the key.
    rejection_reason text not null
        check (rejection_reason in ('', 'INSUFFICIENT_FUNDS', 'SCHEME_REJECTED')),
    payments bigint not null,
    value_pence bigint not null,
    primary key (minute, payment_type, status, rejection_reason)
);
