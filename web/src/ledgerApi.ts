import { infiniteQueryOptions, queryOptions } from "@tanstack/react-query";

/** Live views poll the ledger this often. */
export const POLL_INTERVAL_MS = 2_000;

export type Balance = { accountId: string; balancePence: number };

/** Who a Payment pays: an Account at the fintech, or an account at another UK bank. */
export type Payee =
  | { accountId: string }
  | { sortCode: string; accountNumber: string; name: string };

export type PaymentRequest = {
  debtorAccountId: string;
  payee: Payee;
  amountPence: number;
};

export type PaymentStatus = "PENDING" | "COMPLETED" | "REJECTED";

export type RejectionReason = "INSUFFICIENT_FUNDS" | "SCHEME_REJECTED";

export type Payment = {
  id: string;
  type: "INTERNAL_TRANSFER" | "FASTER_PAYMENT";
  status: PaymentStatus;
  rejectionReason: RejectionReason | null;
  schemeReasonCode: string | null;
};

export type Session = { role: "CUSTOMER" | "OPERATOR" };

/**
 * The CSRF token the ledger keeps in the XSRF-TOKEN cookie, sent back in a header on every POST.
 * Read on each request, because logging in replaces it.
 */
function csrfHeader(): Record<string, string> {
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

/** Makes a Payment. The Idempotency-Key belongs to one user submission, and every retry reuses it. */
export async function makePayment(
  payment: PaymentRequest,
  idempotencyKey: string,
): Promise<Payment> {
  const response = await fetch("/v1/payments", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
      ...csrfHeader(),
    },
    body: JSON.stringify(payment),
  });
  if (!response.ok) throw await problemOf(response);
  return response.json();
}

/** A request the ledger refused with an RFC 9457 problem, such as a validation failure. */
export class ProblemError extends Error {
  readonly type: string;
  readonly detail: string;

  constructor(type: string, detail: string) {
    super(detail);
    this.name = "ProblemError";
    this.type = type;
    this.detail = detail;
  }
}

async function problemOf(response: Response): Promise<Error> {
  if (response.headers.get("Content-Type")?.includes("problem+json")) {
    const problem: { type?: string; title?: string; detail?: string } =
      await response.json();
    if (problem.type) {
      return new ProblemError(
        problem.type,
        problem.detail ?? problem.title ?? problem.type,
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

async function getJson<T>(url: string, what: string): Promise<T> {
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

/** A Payment as it stands now, polled while it is Pending; Completed and Rejected are final. */
export const paymentQuery = (paymentId: string) =>
  queryOptions({
    queryKey: ["payments", paymentId],
    queryFn: () => getJson<Payment>(`/v1/payments/${paymentId}`, "Payment"),
    refetchInterval: (query) =>
      query.state.data?.status === "PENDING" ? POLL_INTERVAL_MS : false,
  });
