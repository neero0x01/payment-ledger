/**
 * A stand-in for the ledger HTTP API, installed as the global fetch, so screen tests drive the real
 * request flow. Like the ledger, it answers 401 until the Customer logs in, sets the CSRF token as the
 * XSRF-TOKEN cookie (replacing it at login), and refuses a POST whose X-XSRF-TOKEN header doesn't
 * match.
 *
 * It keeps Entries per Account and derives everything from them: the ledger's Balance and As-At
 * Balance from all Entries, and the Read Model (GET /v1/accounts, the Statement) from the Entries
 * projected so far. With `trailing: true` new Entries stay unprojected until `project()` is called,
 * so tests can show the Read Model trailing the ledger.
 */

export type FakeAccount = {
  id: string;
  name: string;
  kind?: "CUSTOMER" | "INTERNAL";
  overdraftLimitPence?: number;
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

type BookedEntry = FakeEntry & {
  transactionId: string;
  entrySeq: number;
  balanceAfterPence: number;
};

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
  trailing = false,
  credentials = { username: "local-customer", password: "local-customer" },
  role = "CUSTOMER",
  routes,
}: {
  accounts: FakeAccount[];
  entries?: FakeEntry[];
  trailing?: boolean;
  credentials?: { username: string; password: string };
  role?: "CUSTOMER" | "OPERATOR";
  /** More of the API, answered before the fake's own routes; undefined falls through to them. */
  routes?: (request: FakeRequest) => Response | undefined;
}) {
  const requests: {
    method: string;
    url: string;
    headers: Headers;
    body: unknown;
  }[] = [];
  const booked: BookedEntry[] = [];
  let projected = 0;
  let transactions = 0;
  let loggedIn = false;
  let csrfToken = "csrf-before-login";

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
        (entry.side === "CREDIT" ? entry.amountPence : -entry.amountPence),
    });
  }
  function nextTransactionId() {
    transactions += 1;
    return `019a0000-0000-7000-9000-${String(transactions).padStart(12, "0")}`;
  }
  function project() {
    projected = booked.length;
  }
  for (const entry of entries) {
    book(entry, entry.transactionId ?? nextTransactionId());
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

  const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  const problem = (status: number) =>
    new Response(JSON.stringify({ status }), {
      status,
      headers: { "Content-Type": "application/problem+json" },
    });
  const setCsrfCookie = () => {
    document.cookie = `XSRF-TOKEN=${csrfToken}; path=/`;
  };

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
    if (!loggedIn) return problem(401);

    const routed = routes?.({ method, path, headers, body });
    if (routed) return routed;
    if (method === "GET" && path === "/v1/session") {
      return json(200, { role });
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
      const { debtorAccountId, payee, amountPence } = body;
      const transactionId = nextTransactionId();
      const bookingTime = new Date().toISOString();
      book(
        {
          accountId: debtorAccountId,
          bookingTime,
          side: "DEBIT",
          amountPence,
          counterpartyAccountId: payee.accountId,
        },
        transactionId,
      );
      book(
        {
          accountId: payee.accountId,
          bookingTime,
          side: "CREDIT",
          amountPence,
          counterpartyAccountId: debtorAccountId,
        },
        transactionId,
      );
      if (!trailing) project();
      return json(201, {
        id: "019a0000-0000-7000-8000-0000000000ff",
        type: "INTERNAL_TRANSFER",
        status: "COMPLETED",
        debtorAccountId,
        payee,
        amountPence,
        transactionIds: [transactionId],
      });
    }
    return problem(404);
  };

  vi.stubGlobal("fetch", fetch);
  return { requests, project };
}
