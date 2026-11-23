-- The Read Model's own database in the same Postgres instance (spec §6). The projector migrates
-- and writes it; the ledger's views module reads it through a read-only connection.
create database readmodel;
