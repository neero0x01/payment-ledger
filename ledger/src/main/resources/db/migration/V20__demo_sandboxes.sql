-- The public demo (spec §8). A Customer created for one visitor is a demo Customer, the owner of a
-- Demo Sandbox. Every "Try as operator" visitor acts as the one demo Operator login, which nobody
-- can log in to with a password and which may correct only Transactions on Demo Sandbox Accounts.
-- Existing Customers and logins are not demo ones.

alter table customer add column demo boolean not null default false;

alter table login add column demo boolean not null default false;
