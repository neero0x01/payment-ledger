-- Seeded data is read-only for everyone (spec §8). A seeded Customer is one the seed generator
-- loaded; their Customer Accounts are the seeded Accounts, which no Correction may touch. Internal
-- Accounts are shared by seeded and live data, so they are never seeded. Customers created by the
-- running ledger, Demo Sandbox Customers included, are not seeded.
--
-- The seed loads only into a ledger with no Transactions, so its Customers are the owners of every
-- Customer Account with a source=SEED Entry. That backfills a ledger seeded before this
-- migration, once; the seed marks its Customers itself from now on.

alter table customer add column seeded boolean not null default false;

update customer
set seeded = true
where id in (
    select a.customer_id
    from account a
    join entry e on e.account_id = a.id
    join ledger_transaction t on t.id = e.transaction_id
    where t.source = 'SEED');
