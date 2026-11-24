-- Demo Sandboxes (spec §8): a Customer created for one visitor to the public demo is marked, so
-- the demo Operator can be limited to correcting Transactions on Demo Sandbox Accounts. Existing
-- Customers are not demo Customers.

alter table customer add column demo boolean not null default false;
