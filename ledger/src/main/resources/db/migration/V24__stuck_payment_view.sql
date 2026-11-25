-- The Stuck Payments, named once: Faster Payments still Pending and flagged stuck (V19). The
-- Stuck Payment count and the list of the oldest read this view, so they always agree on what a
-- Stuck Payment is. ledger_app only reads it; Payments change through the payment table.

create view stuck_payment as
select *
from payment
where type = 'FASTER_PAYMENT' and status = 'PENDING' and stuck;

revoke insert, update, delete on stuck_payment from ledger_app;
grant select on stuck_payment to ledger_app;
