# The views module reads other modules' public APIs for the dashboard, and demo is its own module

ADR 0002 and spec §5 drew `views` as depending on nothing but the `readmodel` database, and CODING_STANDARDS rule 14 gave "`views` only reads `readmodel`" as its example. The Operator dashboard (spec §9, screen 4) changed that. Besides the Read Model's figures it shows live write-side health that the Read Model cannot hold:

- the Stuck Payments and their count, from `payments` (`StuckPayments`)
- the outbox lag, and the newest events the Projection Freshness compares against, from `outbox` (`Outbox.lag()` and `Outbox.newest`)
- the last balanced check's result, from `posting` (`BalancedChecks.last()`)

So `views` now reads those modules, always through their public APIs and never their tables or internals (rule 14 still holds). It also asks `accounts` which Accounts a Principal may see, and serves Read Model Balances to the balanced check through posting's `ReadModelBalances` port. None of these modules depends on `views`, so there is no cycle, and Spring Modulith's `verify()` checks it.

The public demo (spec §8) is a module of its own, `demo`. It opens and funds a Demo Sandbox through `accounts` and `posting`; in `accounts` that would make a cycle, because `posting` already depends on `accounts`.

The wall clock that the demo limits and the dashboard measure by is one neutral `Clock` bean in `shared`, so `views` does not lean on a bean that `demo` happened to declare.

## Considered options

- **Project the health figures into the Read Model:** keeps `views` pure, but the outbox lag and the balanced check describe the write side and the pipeline itself; a Read Model that trails would understate exactly the lag it should report.
- **Read the figures from the MeterRegistry by gauge name:** no compile-time dependency, but the coupling is still there, hidden in strings that break silently when a gauge is renamed.
- **A separate health endpoint per module:** more endpoints for the one screen that needs them together.
