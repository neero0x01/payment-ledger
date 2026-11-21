# Payment Ledger

The double-entry ledger of a small UK fintech. It records every movement of customers' money as balanced, permanent Transactions and derives every Balance from them.

## Language

### Parties and accounts

**Customer**:
A person who holds one or more Customer Accounts with the fintech.
_Avoid_: User, client, account holder

**Operator**:
A member of the fintech's staff who watches over the ledger and posts Corrections.
_Avoid_: Admin, back-office user, support agent

**Account**:
A named place in the ledger that Entries are posted to. Every Account is either a Customer Account or an Internal Account.
_Avoid_: Wallet, pot, ledger account

**Customer Account**:
A GBP Account owned by a Customer, known to other banks by its sort code and account number.
_Avoid_: Current account, user account

**Internal Account**:
An Account owned by the fintech itself, such as FPS Clearing, Funding or Corrections.
_Avoid_: System account, house account, GL account

**Clearing Account**:
An Internal Account that holds money in transit between the fintech and a payment scheme. In v1 the only one is FPS Clearing.
_Avoid_: Suspense account, settlement account

**Funding Account**:
The Internal Account that money in Customer Accounts comes from in v1, standing in for inbound payments.
_Avoid_: Treasury, float, source account

**Corrections Account**:
The Internal Account on the other side of every Adjustment.
_Avoid_: Write-off account, errors account

**Normal Side**:
The side, Debit or Credit, on which an Account's Balance counts as positive. Customer Accounts are credit-normal; Funding and FPS Clearing are debit-normal.
_Avoid_: Sign, polarity, direction

### Ledger

**Entry**:
One line of a Transaction: a positive amount posted to one Account on one side. It never changes once posted.
_Avoid_: Posting line, journal line, record, movement

**Debit**:
The side of an Entry that increases a debit-normal Account and decreases a credit-normal one.
_Avoid_: Withdrawal, minus, outflow

**Credit**:
The side of an Entry that increases a credit-normal Account and decreases a debit-normal one.
_Avoid_: Deposit, plus, inflow

**Transaction**:
A set of Entries posted together whose Debits equal its Credits. It never changes once posted.
_Avoid_: Journal, transfer, posting, payment

**Booking Time**:
The moment a Transaction was recorded in the ledger. It is the only timeline in v1.
_Avoid_: Value date, effective date, created date

**Balance**:
The total of an Account's Entries on its Normal Side minus the total on the other side. Always derived from Entries, never set directly.
_Avoid_: Ledger balance, current balance (unqualified)

**As-At Balance**:
An Account's Balance counting only the Entries booked at or before a given moment.
_Avoid_: Historical balance, snapshot, point-in-time balance

**Overdraft Limit**:
How far a Customer Account's Balance may go below zero by arrangement with the fintech. Zero unless arranged.
_Avoid_: Credit limit, arranged overdraft

**Available Balance**:
Balance plus Overdraft Limit: the most a Customer can pay out right now.
_Avoid_: Spendable balance, funds, headroom

**Balanced Ledger**:
The state in which total Debits equal total Credits and every Account's Balance agrees with its Entries.
_Avoid_: Reconciled, consistent

### Payments

**Payment**:
A Customer's instruction to move money out of one of their Customer Accounts. It may lead to zero, one or several Transactions.
_Avoid_: Transaction, transfer, order

**Internal Transfer**:
A Payment whose Payee is another Account at the fintech.
_Avoid_: Book transfer, on-us payment

**Faster Payment**:
A Payment to an account at another UK bank, sent through the Faster Payments Scheme.
_Avoid_: Bank transfer, FPS transfer, outbound payment

**Payee**:
Who a Payment pays: either an Account at the fintech, or an external sort code, account number and name.
_Avoid_: Beneficiary, recipient, creditor

**Payment Status**:
Where a Payment stands. Pending means awaiting a final outcome, Completed means it succeeded, and Rejected means it failed with a Rejection Reason. Completed and Rejected are final.
_Avoid_: State, result

**Rejection Reason**:
Why a Payment was Rejected: Insufficient Funds or Scheme Rejected.
_Avoid_: Error code, decline code

**Idempotency-Key**:
A unique value a client attaches to a Payment or Correction request, so that sending the request again never creates a second one.
_Avoid_: Request id, dedupe key, nonce

### Payment scheme

**Faster Payments Scheme**:
The external UK system that carries Faster Payments between banks. This project uses a simulator in its place.
_Avoid_: The network, the rails

**Scheme Submission**:
Sending a Faster Payment to the Faster Payments Scheme for a decision.
_Avoid_: Dispatch, send, forward

**Scheme Outcome**:
The scheme's final answer to a Scheme Submission: accepted, or rejected with a reason code.
_Avoid_: Callback, response, status report

**Scheme Rejection**:
A Scheme Outcome that refuses the Faster Payment. It makes the Payment Rejected and triggers a Reversal.
_Avoid_: Return, bounce, failure

**End-to-End Id**:
The identifier that travels with a Faster Payment through the scheme, so both sides can recognise a repeated submission.
_Avoid_: Reference, trace id

**Stuck Payment**:
A Faster Payment still Pending after its Scheme Outcome deadline has passed. It is never reversed automatically.
_Avoid_: Timed-out payment, failed payment

### Fixing mistakes

**Reversal**:
A Transaction the ledger posts on its own after a Scheme Rejection, mirroring the original Transaction to return the money.
_Avoid_: Refund, Correction, rollback

**Correction**:
A Transaction posted by an Operator to fix a mistake, linked to the Transaction it corrects, if any. A goodwill Adjustment corrects no Transaction. The original is never changed.
_Avoid_: Amendment, edit, void

**Reversing Correction**:
A Correction that exactly mirrors one whole earlier Transaction.
_Avoid_: Void, cancellation

**Adjustment**:
A Correction that moves an amount between a Customer Account and the Corrections Account.
_Avoid_: Manual entry, fee refund

**Correction Reason**:
Why a Correction was posted: Duplicate Posting, Wrong Amount, Wrong Account, Goodwill or Other.
_Avoid_: Correction type

### Views

**Statement**:
A Customer Account's Statement Lines over a period.
_Avoid_: History, transaction list

**Statement Line**:
One Entry as the Customer sees it: when it was booked, how much, which way, the counterparty, and the Balance after it.
_Avoid_: Row, transaction

**Read Model**:
Ledger data reshaped for viewing (balances, Statements, dashboard figures). It trails the ledger by a short delay.
_Avoid_: Cache, replica, reporting database

**Projection Freshness**:
How far the Read Model currently trails the ledger.
_Avoid_: Delay, sync lag

**Demo Sandbox**:
A brand-new Customer, with funded Customer Accounts, created for one visitor to the public demo.
_Avoid_: Test user, guest account
