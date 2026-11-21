# Entries carry a positive amount and a side; Accounts have a Normal Side

Each Entry stores a positive amount in pence and a side, Debit or Credit, not a signed amount. Each Account has a Normal Side: Customer Accounts are credit-normal (they are the fintech's liability), and Funding and FPS Clearing are debit-normal. Balances are reported on the Account's Normal Side.

This keeps the double-entry rule (Σdebits = Σcredits) visible in the data and in code review, and it matches how accountants and bank finance teams read a ledger. Signed amounts are simpler to sum, but they hide which side each line is on and make "is this Account in credit?" depend on a sign convention the reader has to remember.

## Consequences

Balance arithmetic always goes through the Normal Side: credit-normal Balance = credits − debits, and debit-normal Balance = debits − credits. The "ledger balanced" check compares totals by side.
