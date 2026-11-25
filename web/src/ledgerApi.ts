import { infiniteQueryOptions, queryOptions } from "@tanstack/react-query";
import type { RejectionReason } from "./payments";

/** Live views poll the ledger this often. */
export const POLL_INTERVAL_MS = 2_000;

export type Balance = { accountId: string; balancePence: number };

export type Session = { role: "CUSTOMER" | "OPERATOR" };

/**
 * The CSRF token the ledger keeps in the XSRF-TOKEN cookie, sent back in a header on every POST.
 * Read on each request, because logging in replaces it.
 */
export function csrfHeader(): Record<string, string> {
  const token = document.cookie
    .split("; ")
    .find((cookie) => cookie.startsWith("XSRF-TOKEN="))
    ?.slice("XSRF-TOKEN=".length);
  return token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {};
}

/** Who is logged in, or null before anyone has. The response also sets the CSRF cookie. */
export async function fetchSession(): Promise<Session | null> {
  const response = await fetch("/v1/session");
  if (response.status === 401) return null;
  if (!response.ok) {
    throw new Error(`Session failed with ${response.status}`);
  }
  return response.json();
}

/** Logs in, which starts a session the ledger keeps in a cookie. */
export async function logIn(username: string, password: string) {
  const response = await fetch("/v1/sessions", {
    method: "POST",
    headers: csrfHeader(),
    body: new URLSearchParams({ username, password }),
  });
  if (!response.ok) {
    throw new Error(`Login failed with ${response.status}`);
  }
}

/**
 * Starts a public demo session, which logs the visitor in without a password: as the Customer of a
 * new, funded Demo Sandbox, or as the demo Operator. Over the demo's limits it fails with a
 * rate-limited problem.
 */
export async function startDemoSession(role: Session["role"]) {
  const response = await fetch("/v1/demo-sessions", {
    method: "POST",
    headers: { "Content-Type": "application/json", ...csrfHeader() },
    body: JSON.stringify({ role }),
  });
  if (!response.ok) throw await problemOf(response);
}

/** A request the ledger refused with an RFC 9457 problem, such as a validation failure. */
export class ProblemError extends Error {
  readonly type: string;
  readonly detail: string;
  /** The request field the problem is about, by its path in the JSON body, if it names one. */
  readonly field: string | undefined;

  constructor(type: string, detail: string, field?: string) {
    super(detail);
    this.name = "ProblemError";
    this.type = type;
    this.detail = detail;
    this.field = field;
  }
}

export async function problemOf(response: Response): Promise<Error> {
  if (response.headers.get("Content-Type")?.includes("problem+json")) {
    const problem: {
      type?: string;
      title?: string;
      detail?: string;
      field?: string;
    } = await response.json();
    if (problem.type) {
      return new ProblemError(
        problem.type,
        problem.detail ?? problem.title ?? problem.type,
        problem.field,
      );
    }
  }
  return new Error(`the ledger answered ${response.status}`);
}

/** An Account as the Read Model shows it: its Balance trails the ledger by the Projection Freshness. */
export type Account = {
  id: string;
  name: string;
  kind: "CUSTOMER" | "INTERNAL";
  balancePence: number;
  overdraftLimitPence: number;
  availableBalancePence: number;
};

export async function getJson<T>(url: string, what: string): Promise<T> {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`${what} failed with ${response.status}`);
  }
  return response.json();
}

export const accountsQuery = () =>
  queryOptions({
    queryKey: ["accounts"],
    queryFn: () => getJson<Account[]>("/v1/accounts", "Accounts"),
    refetchInterval: POLL_INTERVAL_MS,
  });

export const accountQuery = (accountId: string) =>
  queryOptions({
    queryKey: ["accounts", accountId],
    queryFn: () => getJson<Account>(`/v1/accounts/${accountId}`, "Account"),
    refetchInterval: POLL_INTERVAL_MS,
  });

/** One Entry as the Customer sees it, from the Read Model. */
export type StatementLine = {
  transactionId: string;
  bookingTime: string;
  entrySeq: number;
  side: "DEBIT" | "CREDIT";
  amountPence: number;
  balanceAfterPence: number;
  counterpartyAccountId: string | null;
};

export type StatementPage = {
  accountId: string;
  lines: StatementLine[];
  nextCursor: string | null;
};

/** A Statement's Booking Time range: from inclusive, to exclusive, both optional. */
export type StatementRange = { from?: string; to?: string };

/** An Account's Statement, a page of 50 Statement Lines at a time, each page reached by a cursor. */
export const statementQuery = (accountId: string, range: StatementRange) =>
  infiniteQueryOptions({
    queryKey: ["accounts", accountId, "statement", range],
    initialPageParam: null as string | null,
    getNextPageParam: (page: StatementPage) => page.nextCursor,
    queryFn: ({ pageParam }) => {
      const query = new URLSearchParams();
      if (range.from) query.set("from", range.from);
      if (range.to) query.set("to", range.to);
      if (pageParam) query.set("cursor", pageParam);
      const search = query.size > 0 ? `?${query}` : "";
      return getJson<StatementPage>(
        `/v1/accounts/${accountId}/statement${search}`,
        "Statement",
      );
    },
    refetchInterval: POLL_INTERVAL_MS,
  });

/** The ledger's authoritative Balance, or its As-At Balance when asAt is given. */
export const balanceQuery = (accountId: string, asAt?: string) =>
  queryOptions({
    queryKey: ["balance", accountId, asAt ?? "now"],
    queryFn: () =>
      getJson<Balance>(
        `/v1/accounts/${accountId}/balance${
          asAt ? `?${new URLSearchParams({ asAt })}` : ""
        }`,
        "Balance",
      ),
    // A past As-At Balance never changes; the Balance now is a live view.
    refetchInterval: asAt ? false : POLL_INTERVAL_MS,
  });

/** A Faster Payment still Pending after its Scheme Outcome deadline. */
export type StuckPayment = {
  paymentId: string;
  debtorAccountId: string;
  payeeName: string;
  payeeSortCode: string;
  payeeAccountNumber: string;
  amountPence: number;
  madeAt: string;
};

/**
 * The Operator dashboard: per-minute Read Model figures (the rate over the last full minute,
 * outcomes and Rejection Reasons over the last hour) and the ledger's gauges. A gauge with no
 * reading is null; the balanced check's fields are null before its first run.
 */
export type Dashboard = {
  paymentsPerSecond: number;
  outcomes: { completed: number; rejected: number };
  rejectionReasons: Record<RejectionReason, number>;
  outboxLag: { unpublished: number | null; oldestAgeSeconds: number | null };
  projectionFreshnessSeconds: number | null;
  balancedCheck: {
    balanced: boolean | null;
    imbalancePence: number | null;
    lastRunAt: string | null;
  };
  stuckPaymentCount: number | null;
  stuckPayments: StuckPayment[];
};

export const dashboardQuery = () =>
  queryOptions({
    queryKey: ["dashboard"],
    queryFn: () => getJson<Dashboard>("/v1/dashboard", "Dashboard"),
    refetchInterval: POLL_INTERVAL_MS,
  });
