/**
 * A stand-in for the ledger HTTP API, installed as the global fetch. It keeps Balances in pence and
 * applies Internal Transfers the way the ledger does, so page tests drive the real request flow. Like
 * the ledger, it answers 401 until the Customer logs in, sets the CSRF token as the XSRF-TOKEN
 * cookie (replacing it at login), and refuses a POST whose X-XSRF-TOKEN header doesn't match.
 */
export function installFakeLedger(
  balances: Record<string, number>,
  credentials = { username: "local-customer", password: "local-customer" },
) {
  const requests: {
    method: string;
    url: string;
    headers: Headers;
    body: unknown;
  }[] = [];
  let loggedIn = false;
  let csrfToken = "csrf-before-login";
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

  const fetch = async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = input.toString();
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
    if (method === "POST" && url === "/v1/sessions") {
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

    if (method === "GET" && url === "/v1/session") {
      return json(200, { role: "CUSTOMER" });
    }
    const balance = url.match(/^\/v1\/accounts\/([^/]+)\/balance$/);
    const accountId = balance?.[1];
    if (method === "GET" && accountId) {
      if (!(accountId in balances)) return json(404, { status: 404 });
      return json(200, { accountId, balancePence: balances[accountId] });
    }
    if (method === "POST" && url === "/v1/payments") {
      const { debtorAccountId, payee, amountPence } = body;
      balances[debtorAccountId] =
        (balances[debtorAccountId] ?? 0) - amountPence;
      balances[payee.accountId] =
        (balances[payee.accountId] ?? 0) + amountPence;
      return json(201, {
        id: "019a0000-0000-7000-8000-0000000000ff",
        type: "INTERNAL_TRANSFER",
        status: "COMPLETED",
        debtorAccountId,
        payee,
        amountPence,
        transactionIds: [],
      });
    }
    return json(404, { status: 404 });
  };

  vi.stubGlobal("fetch", fetch);
  return { requests };
}
