# Booking time is the only timeline; no value dates in v1

Entries carry one timestamp, `booked_at`. As-At Balances answer "what had the ledger recorded at that moment?". A Correction booked on 2026-11-05 for an error booked on 2026-10-30 therefore doesn't change the As-At Balance at 2026-11-01.

Bi-temporal modelling (booking time plus value date, which allows back-valued corrections) is what real banks use for interest and statements. It doubles the complexity of every balance query, and nothing in v1 needs it. It comes back with interest accrual in v2, as an added column on Entries.
