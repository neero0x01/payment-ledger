export type Balance = { accountId: string; balancePence: number };

export type InternalTransfer = {
  debtorAccountId: string;
  payeeAccountId: string;
  amountPence: number;
};

export type Payment = { id: string; status: string };

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

/** Makes an Internal Transfer. The Idempotency-Key belongs to one user submission. */
export async function makeInternalTransfer(
  transfer: InternalTransfer,
  idempotencyKey: string,
): Promise<Payment> {
  const response = await fetch("/v1/payments", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
      ...csrfHeader(),
    },
    body: JSON.stringify({
      debtorAccountId: transfer.debtorAccountId,
      payee: { accountId: transfer.payeeAccountId },
      amountPence: transfer.amountPence,
    }),
  });
  if (!response.ok) {
    throw new Error(`Payment failed with ${response.status}`);
  }
  return response.json();
}

export async function fetchBalance(accountId: string): Promise<Balance> {
  const response = await fetch(`/v1/accounts/${accountId}/balance`);
  if (!response.ok) {
    throw new Error(`Balance of ${accountId} failed with ${response.status}`);
  }
  return response.json();
}
