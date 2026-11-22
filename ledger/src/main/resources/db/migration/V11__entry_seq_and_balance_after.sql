-- Each Entry records its Account's entry_seq (1, 2, 3, … per Account, in the order the Account's
-- balance row was locked) and balance_after, the Account's Balance on its Normal Side just after
-- the Entry (spec §2 rule 2, ADR 0004). The balance row keeps the last entry_seq handed out.

alter table account_balance add column last_entry_seq bigint not null default 0;

alter table entry
    add column entry_seq           bigint,
    add column balance_after_pence bigint;

-- Entries posted before this migration are numbered in Booking Time order.
with numbered as (
    select e.id,
           row_number() over account_timeline as entry_seq,
           sum(case when e.side = a.normal_side then e.amount_pence else -e.amount_pence end)
               over account_timeline as balance_after_pence
    from entry e
    join account a on a.id = e.account_id
    window account_timeline as (partition by e.account_id order by e.booked_at, e.id)
)
update entry
set entry_seq = numbered.entry_seq,
    balance_after_pence = numbered.balance_after_pence
from numbered
where entry.id = numbered.id;

update account_balance
set last_entry_seq = latest.entry_seq
from (select account_id, max(entry_seq) as entry_seq from entry group by account_id) latest
where account_balance.account_id = latest.account_id;

alter table entry
    alter column entry_seq set not null,
    alter column balance_after_pence set not null,
    add constraint entry_entry_seq_positive check (entry_seq > 0);
