import type { Payment } from "./payments";

/**
 * A stand-in for the ledger HTTP API, installed as the global fetch, so screen tests drive the real
 * request flow. Like the ledger, it answers 401 until the Customer or Operator logs in, sets the CSRF
 * token as the XSRF-TOKEN cookie (replacing it at login), and refuses a POST whose X-XSRF-TOKEN
 * header doesn't match.
 *
 * It keeps one set of Transactions and Entries, and derives everything from them: each Entry's
 * balance-after on its Account's Normal Side, the ledger's Balance and As-At Balance from all
 * Entries, and the Read Model (GET /v1/accounts, the Statement) from the Entries projected so far.
 * With `trailing: true` new Entries stay unprojected until `project()` is called, so tests can show
 * the Read Model trailing the ledger. Payments, Reversals and Corrections all post Transactions into
 * the same set, so a Correction moves the Balances and the Statement.
 *
 * Like the ledger it also serves GET /v1/transactions/{id}, GET /v1/payments/{id} and POST
 * /v1/corrections, which refuses a second reversal as already-reversed, a Correction of a
 * Transaction whose Payment is Pending as payment-pending, and a Correction of a seeded Transaction
 * as seed-data-read-only, and replays a Correction whose Idempotency-Key it has seen.
 */

export type FakeAccount = {
  id: string;
  name: string;
  kind?: "CUSTOMER" | "INTERNAL";
  overdraftLimitPence?: number;
  /** Customer Accounts are credit-normal by default; Internal Accounts are debit-normal. */
  normalSide?: "DEBIT" | "CREDIT";
};

export type FakeEntry = {
  accountId: string;
  bookingTime: string;
  side: "DEBIT" | "CREDIT";
  amountPence: number;
  counterpartyAccountId?: string | null;
  /** The Transaction the Entry belongs to; by default each seeded Entry gets one of its own. */
  transactionId?: string;
};

/** A Transaction to seed: its Entries are booked at its Booking Time, in order. */
export type FakeTransaction = {
  id: string;
  kind: "PAYMENT" | "REVERSAL" | "FUNDING" | "CORRECTION";
  bookingTime: string;
  paymentId?: string;
  reversesTransactionId?: string;
  adjustsTransactionId?: string;
  correctionReason?: string;
  correctionNote?: string;
  source?: "LIVE" | "SEED";
  entries: {
    accountId: string;
    side: "DEBIT" | "CREDIT";
    amountPence: number;
    counterpartyAccountId?: string | null;
  }[];
};

export type FakePayment = {
  id: string;
  type: Payment["type"];
  status: Payment["status"];
  debtorAccountId: string;
  payee: {
    accountId?: string;
    sortCode?: string;
    accountNumber?: string;
    name?: string;
  };
  amountPence: number;
  rejectionReason?: Payment["rejectionReason"];
  schemeReasonCode?: string;
};

type BookedEntry = FakeEntry & {
  transactionId: string;
  entrySeq: number;
  balanceAfterPence: number;
};

type Side = "DEBIT" | "CREDIT";

type CorrectionBody = {
  kind?: "REVERSING" | "ADJUSTMENT";
  transactionId?: string;
  reason?: string;
  note?: string;
  accountId?: string;
  amountPence?: number;
  direction?: Side;
};

const opposite = (side: Side): Side => (side === "DEBIT" ? "CREDIT" : "DEBIT");

export const STATEMENT_PAGE_SIZE = 50;

/** Credit-normal Customer Accounts, each funded with one Entry booked at the given moment. */
export function funded(
  balances: Record<string, number>,
  bookingTime = "2026-11-01T09:00:00Z",
): FakeEntry[] {
  return Object.entries(balances)
    .filter(([, pence]) => pence > 0)
    .map(([accountId, amountPence]) => ({
      accountId,
      bookingTime,
      side: "CREDIT",
      amountPence,
      counterpartyAccountId: null,
    }));
}

/** A request the fake hands, once logged in, to the routes a test adds. */
export type FakeRequest = {
  method: string;
  path: string;
  headers: Headers;
  body: unknown;
};

export function installFakeLedger({
  accounts,
  entries = [],
  transactions: seededTransactions = [],
  payments: seededPayments = [],
  trailing = false,
  credentials = { username: "local-customer", password: "local-customer" },
  role = "CUSTOMER",
  routes,
  demoRateLimited = false,
}: {
  accounts: FakeAccount[];
  entries?: FakeEntry[];
  /** Transactions booked after the loose `entries`, each with its kind and links. */
  transactions?: FakeTransaction[];
  payments?: FakePayment[];
  trailing?: boolean;
  credentials?: { username: string; password: string };
  role?: "CUSTOMER" | "OPERATOR";
  /** More of the API, answered before the fake's own routes; undefined falls through to them. */
  routes?: (request: FakeRequest) => Response | undefined;
  /** Refuse every demo session as over the demo's limits, as the ledger does with a 429. */
  demoRateLimited?: boolean;
}) {
  const requests: {
    method: string;
    url: string;
    headers: Headers;
    body: unknown;
  }[] = [];
  const booked: BookedEntry[] = [];
  let projected = 0;
  const transactions: Omit<FakeTransaction, "entries">[] = [];
  const payments: FakePayment[] = [...seededPayments];
  const byIdempotencyKey = new Map<
    string,
    { request: string; payment: FakePayment }
  >();
  const correctionsByKey = new Map<string, string>();
  let loggedIn = false;
  let sessionRole = role;
  let csrfToken = "csrf-before-login";

  const normalSideOf = (accountId: string): Side => {
    const account = accounts.find((a) => a.id === accountId);
    return (
      account?.normalSide ?? (account?.kind === "INTERNAL" ? "DEBIT" : "CREDIT")
    );
  };
  function book(entry: FakeEntry, transactionId: string) {
    const previous = booked.filter((e) => e.accountId === entry.accountId);
    const before = previous.at(-1)?.balanceAfterPence ?? 0;
    booked.push({
      ...entry,
      counterpartyAccountId: entry.counterpartyAccountId ?? null,
      transactionId,
      entrySeq: previous.length + 1,
      balanceAfterPence:
        before +
        (entry.side === normalSideOf(entry.accountId)
          ? entry.amountPence
          : -entry.amountPence),
    });
  }
  let generated = 0;
  function nextId(prefix: string, taken: (id: string) => boolean) {
    let id: string;
    do {
      generated += 1;
      id = `019a0000-0000-7000-${prefix}-${String(generated).padStart(12, "0")}`;
    } while (taken(id));
    return id;
  }
  const nextTransactionId = () =>
    nextId(
      "9000",
      (id) =>
        transactions.some((t) => t.id === id) ||
        seededTransactions.some((t) => t.id === id),
    );
  function project() {
    projected = booked.length;
  }
  /** Posts a Transaction: its Entries share one id and one Booking Time. */
  function post(
    transaction: Omit<FakeTransaction, "id" | "entries"> & { id?: string },
    entriesOf: FakeTransaction["entries"],
  ) {
    const id = transaction.id ?? nextTransactionId();
    const posted = { ...transaction, id };
    transactions.push(posted);
    for (const entry of entriesOf) {
      book({ ...entry, bookingTime: transaction.bookingTime }, id);
    }
    return posted;
  }
  for (const entry of entries) {
    const id = entry.transactionId ?? nextTransactionId();
    if (!transactions.some((t) => t.id === id)) {
      transactions.push({
        id,
        kind: "FUNDING",
        bookingTime: entry.bookingTime,
      });
    }
    book(entry, id);
  }
  for (const { entries: entriesOf, ...transaction } of seededTransactions) {
    post(transaction, entriesOf);
  }
  project();

  const balanceOf = (accountId: string, of: BookedEntry[]) =>
    of.filter((e) => e.accountId === accountId).at(-1)?.balanceAfterPence ?? 0;
  const readModel = () => booked.slice(0, projected);
  const accountView = (account: FakeAccount) => {
    const balancePence = balanceOf(account.id, readModel());
    const overdraftLimitPence = account.overdraftLimitPence ?? 0;
    return {
      id: account.id,
      name: account.name,
      kind: account.kind ?? "CUSTOMER",
      balancePence,
      overdraftLimitPence,
      availableBalancePence: balancePence + overdraftLimitPence,
    };
  };
  const entriesOf = (transactionId: string) =>
    booked.filter((e) => e.transactionId === transactionId);

  const reversalOf = (id: string) =>
    transactions.find((t) => t.reversesTransactionId === id)?.id ?? null;
  const correctsOf = (t: Omit<FakeTransaction, "entries">) =>
    t.kind === "CORRECTION"
      ? (t.reversesTransactionId ?? t.adjustsTransactionId ?? null)
      : null;
  const transactionView = (t: Omit<FakeTransaction, "entries">) => ({
    id: t.id,
    kind: t.kind,
    bookingTime: t.bookingTime,
    paymentId: t.paymentId ?? null,
    reversesTransactionId: t.reversesTransactionId ?? null,
    correctionReason: t.correctionReason ?? null,
    correctionNote: t.correctionNote ?? null,
    source: t.source ?? "LIVE",
    entries: entriesOf(t.id).map((e) => ({
      accountId: e.accountId,
      side: e.side,
      amountPence: e.amountPence,
      entrySeq: e.entrySeq,
      balanceAfterPence: e.balanceAfterPence,
    })),
    reversedByTransactionId: reversalOf(t.id),
    correctsTransactionId: correctsOf(t),
    correctedByTransactionIds: transactions
      .filter((other) => correctsOf(other) === t.id)
      .map((other) => other.id),
  });
  const paymentView = (p: FakePayment): Payment => ({
    id: p.id,
    type: p.type,
    status: p.status,
    debtorAccountId: p.debtorAccountId,
    payee: {
      accountId: p.payee.accountId ?? null,
      sortCode: p.payee.sortCode ?? null,
      accountNumber: p.payee.accountNumber ?? null,
      name: p.payee.name ?? null,
    },
    amountPence: p.amountPence,
    rejectionReason: p.rejectionReason ?? null,
    schemeReasonCode: p.schemeReasonCode ?? null,
    transactionIds: transactions
      .filter((t) => t.paymentId === p.id)
      .map((t) => t.id),
  });

  /**
   * Like the ledger: an Internal Transfer is Completed at once, and a Faster Payment debits the
   * payer and stays Pending until `decide` gives its Scheme Outcome.
   */
  function makePayment(request: {
    debtorAccountId: string;
    payee: { accountId?: string };
    amountPence: number;
  }): FakePayment {
    const { debtorAccountId, payee, amountPence } = request;
    const internal = payee.accountId !== undefined;
    const id = `019a0000-0000-7000-8000-${String(payments.length + 1).padStart(12, "0")}`;
    const type = internal ? "INTERNAL_TRANSFER" : "FASTER_PAYMENT";
    const debtor = accounts.find((a) => a.id === debtorAccountId);
    const available =
      balanceOf(debtorAccountId, booked) + (debtor?.overdraftLimitPence ?? 0);
    if (amountPence > available) {
      const rejected: FakePayment = {
        id,
        type,
        status: "REJECTED",
        debtorAccountId,
        payee,
        amountPence,
        rejectionReason: "INSUFFICIENT_FUNDS",
      };
      payments.push(rejected);
      return rejected;
    }
    post(
      { kind: "PAYMENT", bookingTime: new Date().toISOString(), paymentId: id },
      [
        {
          accountId: debtorAccountId,
          side: "DEBIT",
          amountPence,
          counterpartyAccountId: payee.accountId ?? null,
        },
        ...(payee.accountId === undefined
          ? []
          : [
              {
                accountId: payee.accountId,
                side: "CREDIT" as const,
                amountPence,
                counterpartyAccountId: debtorAccountId,
              },
            ]),
      ],
    );
    if (!trailing) project();
    const payment: FakePayment = {
      id,
      type,
      status: internal ? "COMPLETED" : "PENDING",
      debtorAccountId,
      payee,
      amountPence,
    };
    payments.push(payment);
    return payment;
  }

  /** Mirrors a Transaction's Entries, each on the opposite side. */
  const mirrorOf = (transactionId: string) =>
    entriesOf(transactionId).map((e) => ({
      accountId: e.accountId,
      side: opposite(e.side),
      amountPence: e.amountPence,
      counterpartyAccountId: e.counterpartyAccountId,
    }));

  /**
   * The Scheme Outcome of the latest Pending Faster Payment. A Scheme Rejection makes the Payment
   * Rejected and posts a Reversal of its Transaction, as the ledger does.
   */
  function decide(
    outcome:
      | { status: "COMPLETED" }
      | { status: "REJECTED"; schemeReasonCode: string },
  ) {
    const payment = payments.findLast((p) => p.status === "PENDING");
    if (!payment) throw new Error("No Faster Payment is Pending");
    payment.status = outcome.status;
    if (outcome.status === "REJECTED") {
      payment.rejectionReason = "SCHEME_REJECTED";
      payment.schemeReasonCode = outcome.schemeReasonCode;
      for (const original of transactions.filter(
        (t) => t.paymentId === payment.id,
      )) {
        post(
          {
            kind: "REVERSAL",
            bookingTime: new Date().toISOString(),
            paymentId: payment.id,
            reversesTransactionId: original.id,
          },
          mirrorOf(original.id),
        );
      }
      if (!trailing) project();
    }
  }

  const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  const problem = (status: number, slug?: string, detail?: string) =>
    new Response(
      JSON.stringify({
        ...(slug && { type: `/problems/${slug}` }),
        status,
        ...(detail && { detail }),
      }),
      { status, headers: { "Content-Type": "application/problem+json" } },
    );
  const setCsrfCookie = () => {
    document.cookie = `XSRF-TOKEN=${csrfToken}; path=/`;
  };

  /** Posts a Correction, or answers with the problem the ledger would refuse it with. */
  function correct(body: CorrectionBody, key: string): Response {
    if (!body.reason || !body.note?.trim()) {
      return problem(
        400,
        "validation-failed",
        "A Correction needs a Correction Reason and a note",
      );
    }
    const corrected = body.transactionId
      ? transactions.find((t) => t.id === body.transactionId)
      : undefined;
    if (body.transactionId && !corrected) {
      return problem(
        404,
        "not-found",
        `No Transaction ${body.transactionId} exists`,
      );
    }
    if (corrected?.source === "SEED") {
      return problem(
        409,
        "seed-data-read-only",
        "Seeded data is read-only: no Correction may touch it",
      );
    }
    const payment = payments.find((p) => p.id === corrected?.paymentId);
    if (payment?.status === "PENDING") {
      return problem(
        409,
        "payment-pending",
        `Transaction ${corrected?.id} belongs to Payment ${payment.id}, which is still Pending`,
      );
    }
    let posted: FakeTransaction["entries"];
    if (body.kind === "REVERSING") {
      if (!corrected) {
        return problem(
          400,
          "validation-failed",
          "A Reversing Correction needs a transactionId",
        );
      }
      if (reversalOf(corrected.id)) {
        return problem(
          409,
          "already-reversed",
          `Transaction ${corrected.id} is already reversed`,
        );
      }
      posted = mirrorOf(corrected.id);
    } else {
      if (!body.accountId || !body.amountPence || !body.direction) {
        return problem(
          400,
          "validation-failed",
          "An Adjustment needs an accountId, an amountPence and a direction",
        );
      }
      const correctionsAccount = accounts.find((a) => a.name === "Corrections");
      if (!correctionsAccount) {
        throw new Error("The fake ledger has no Corrections Account");
      }
      posted = [
        {
          accountId: correctionsAccount.id,
          side: opposite(body.direction),
          amountPence: body.amountPence,
          counterpartyAccountId: body.accountId,
        },
        {
          accountId: body.accountId,
          side: body.direction,
          amountPence: body.amountPence,
          counterpartyAccountId: correctionsAccount.id,
        },
      ];
    }
    const correction = post(
      {
        id: nextId("a000", (taken) => transactions.some((t) => t.id === taken)),
        kind: "CORRECTION",
        bookingTime: new Date().toISOString(),
        reversesTransactionId:
          body.kind === "REVERSING" ? corrected?.id : undefined,
        adjustsTransactionId:
          body.kind === "ADJUSTMENT" ? corrected?.id : undefined,
        correctionReason: body.reason,
        correctionNote: body.note,
      },
      posted,
    );
    if (!trailing) project();
    correctionsByKey.set(key, correction.id);
    return json(201, transactionView(correction));
  }

  function statement(accountId: string, query: URLSearchParams) {
    const from = query.get("from");
    const to = query.get("to");
    const lines = readModel()
      .filter((e) => e.accountId === accountId)
      .filter((e) => !from || Date.parse(e.bookingTime) >= Date.parse(from))
      .filter((e) => !to || Date.parse(e.bookingTime) < Date.parse(to))
      .sort(
        (a, b) =>
          Date.parse(b.bookingTime) - Date.parse(a.bookingTime) ||
          b.entrySeq - a.entrySeq,
      );
    const start = Number(query.get("cursor") ?? 0);
    const page = lines.slice(start, start + STATEMENT_PAGE_SIZE);
    const next = start + STATEMENT_PAGE_SIZE;
    return {
      accountId,
      lines: page.map((e) => ({
        transactionId: e.transactionId,
        bookingTime: e.bookingTime,
        entrySeq: e.entrySeq,
        side: e.side,
        amountPence: e.amountPence,
        balanceAfterPence: e.balanceAfterPence,
        counterpartyAccountId: e.counterpartyAccountId,
      })),
      nextCursor: next < lines.length ? String(next) : null,
    };
  }

  const fetch = async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = input.toString();
    const [path = "", search = ""] = url.split("?");
    const query = new URLSearchParams(search);
    const method = init.method ?? "GET";
    const headers = new Headers(init.headers);
    const body =
      init.body instanceof URLSearchParams
        ? Object.fromEntries(init.body)
        : init.body
          ? JSON.parse(init.body.toString())
          : undefined;
    requests.push({ method, url, headers, body });
    setCsrfCookie();

    if (method === "POST" && headers.get("X-XSRF-TOKEN") !== csrfToken) {
      return problem(403);
    }
    if (method === "POST" && path === "/v1/sessions") {
      if (
        body.username !== credentials.username ||
        body.password !== credentials.password
      ) {
        return problem(401);
      }
      loggedIn = true;
      csrfToken = "csrf-after-login";
      setCsrfCookie();
      return new Response(null, { status: 204 });
    }
    // A demo session logs in as the Customer of a Demo Sandbox (the fake's Accounts) or as the
    // demo Operator.
    if (method === "POST" && path === "/v1/demo-sessions") {
      if (demoRateLimited) {
        return new Response(
          JSON.stringify({
            type: "/problems/rate-limited",
            title: "Rate limited",
            status: 429,
            detail:
              "One Demo Sandbox per visitor every 10 minutes; try again later",
          }),
          {
            status: 429,
            headers: { "Content-Type": "application/problem+json" },
          },
        );
      }
      loggedIn = true;
      sessionRole = body.role;
      return json(201, {
        role: sessionRole,
        customerId:
          sessionRole === "CUSTOMER"
            ? "019a0000-0000-7000-8000-0000000000cc"
            : null,
        accountIds: sessionRole === "CUSTOMER" ? accounts.map((a) => a.id) : [],
      });
    }
    if (!loggedIn) return problem(401);

    const routed = routes?.({ method, path, headers, body });
    if (routed) return routed;
    if (method === "GET" && path === "/v1/session") {
      return json(200, { role: sessionRole });
    }
    if (method === "GET" && path === "/v1/accounts") {
      return json(200, accounts.map(accountView));
    }
    const [, accountId, resource] =
      path.match(/^\/v1\/accounts\/([^/]+)(?:\/(balance|statement))?$/) ?? [];
    const account = accounts.find((a) => a.id === accountId);
    if (method === "GET" && accountId) {
      if (!account) return problem(404);
      if (resource === "statement")
        return json(200, statement(account.id, query));
      if (resource === "balance") {
        const asAt = query.get("asAt");
        const upTo = asAt
          ? booked.filter((e) => Date.parse(e.bookingTime) <= Date.parse(asAt))
          : booked;
        return json(200, {
          accountId,
          balancePence: balanceOf(account.id, upTo),
        });
      }
      return json(200, accountView(account));
    }
    if (method === "POST" && path === "/v1/payments") {
      // Like the ledger, a resent Idempotency-Key replays the Payment as it is now.
      const key = headers.get("Idempotency-Key");
      if (!key) return problem(400);
      const made = byIdempotencyKey.get(key);
      if (made) {
        if (made.request !== JSON.stringify(body)) return problem(422);
        return new Response(JSON.stringify(paymentView(made.payment)), {
          status: 200,
          headers: {
            "Content-Type": "application/json",
            "Idempotent-Replayed": "true",
          },
        });
      }
      const payment = makePayment(body);
      byIdempotencyKey.set(key, { request: JSON.stringify(body), payment });
      return json(201, paymentView(payment));
    }
    const [, paymentId] = path.match(/^\/v1\/payments\/([^/]+)$/) ?? [];
    const payment = payments.find((p) => p.id === paymentId);
    if (method === "GET" && paymentId) {
      return payment
        ? json(200, paymentView(payment))
        : problem(404, "not-found", `No Payment ${paymentId}`);
    }
    const [, transactionId] = path.match(/^\/v1\/transactions\/([^/]+)$/) ?? [];
    const transaction = transactions.find((t) => t.id === transactionId);
    if (method === "GET" && transactionId) {
      return transaction
        ? json(200, transactionView(transaction))
        : problem(404, "not-found", `No Transaction ${transactionId} exists`);
    }
    if (method === "POST" && path === "/v1/corrections") {
      const key = headers.get("Idempotency-Key");
      if (!key) {
        return problem(
          400,
          "idempotency-key-missing",
          "An Idempotency-Key is required",
        );
      }
      const replayed = transactions.find(
        (t) => t.id === correctionsByKey.get(key),
      );
      if (replayed) return json(200, transactionView(replayed));
      return correct(body as CorrectionBody, key);
    }
    return problem(404);
  };

  vi.stubGlobal("fetch", fetch);
  return { requests, project, decide, payments };
}
