-- The role the application runs as. Flyway migrates as the schema owner; every application
-- connection then runs `set role ledger_app` (spring.datasource.hikari.connection-init-sql), so
-- the running ledger has only the privileges granted here. Entries and Transactions are
-- append-only: INSERT and SELECT, never UPDATE, DELETE or TRUNCATE (spec §2, rule 3).

do $$
begin
    if not exists (select from pg_roles where rolname = 'ledger_app') then
        create role ledger_app nologin;
    end if;
end;
$$;

-- Lets a schema owner that is not a superuser switch to the role.
grant ledger_app to current_user;

grant usage on schema public to ledger_app;

grant select, insert on ledger_transaction, entry to ledger_app;
grant select, insert on customer, account to ledger_app;
grant select, insert, update on payment, account_balance to ledger_app;

-- Tables added by later migrations are mutable by default (Payments, outbox rows). A later
-- append-only table must revoke UPDATE from ledger_app in its own migration.
alter default privileges in schema public grant select, insert, update on tables to ledger_app;
alter default privileges in schema public grant usage on sequences to ledger_app;
