/**
 * A stand-in for the ledger HTTP API, installed as the global fetch. It keeps Balances in pence and
 * applies Internal Transfers the way the ledger does, so page tests drive the real request flow.
 */
export function installFakeLedger(balances: Record<string, number>) {
  const requests: {
    method: string;
    url: string;
    headers: Headers;
    body: unknown;
  }[] = [];
  const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });

  const fetch = async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = input.toString();
    const method = init.method ?? "GET";
    const body = init.body ? JSON.parse(init.body.toString()) : undefined;
    requests.push({ method, url, headers: new Headers(init.headers), body });

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
