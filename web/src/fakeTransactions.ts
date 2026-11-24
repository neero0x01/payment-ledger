import type { FakeRequest } from "./fakeLedger";

/**
 * The write side of the ledger HTTP API for Operator screens, added to the fake ledger as routes:
 * GET /v1/transactions/{id}, GET /v1/payments/{id} and POST /v1/corrections. Like the ledger, it
 * refuses a second reversal as already-reversed, a Correction of a Transaction whose Payment is
 * Pending as payment-pending, and replays a Correction whose Idempotency-Key it has seen.
 */

export type FakeTransactionEntry = {
  accountId: string;
  side: "DEBIT" | "CREDIT";
  amountPence: number;
  balanceAfterPence: number;
};

export type FakeTransaction = {
  id: string;
  kind: "PAYMENT" | "REVERSAL" | "FUNDING" | "CORRECTION";
  bookingTime: string;
  paymentId?: string;
  reversesTransactionId?: string;
  correctionReason?: string;
  correctionNote?: string;
  entries: FakeTransactionEntry[];
};

export type FakePayment = {
  id: string;
  type: "INTERNAL_TRANSFER" | "FASTER_PAYMENT";
  status: "PENDING" | "COMPLETED" | "REJECTED";
  debtorAccountId: string;
  payee: {
    accountId?: string;
    sortCode?: string;
    accountNumber?: string;
    name?: string;
  };
  amountPence: number;
  rejectionReason?: string;
};

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

const problem = (status: number, slug: string, detail: string) =>
  new Response(JSON.stringify({ type: `/problems/${slug}`, status, detail }), {
    status,
    headers: { "Content-Type": "application/problem+json" },
  });

type CorrectionBody = {
  kind?: "REVERSING" | "ADJUSTMENT";
  transactionId?: string;
  reason?: string;
  note?: string;
  accountId?: string;
  amountPence?: number;
  direction?: "DEBIT" | "CREDIT";
};

export function fakeTransactions({
  transactions,
  payments = [],
  correctionsAccountId,
}: {
  transactions: FakeTransaction[];
  payments?: FakePayment[];
  correctionsAccountId: string;
}) {
  const posted = [...transactions];
  const byKey = new Map<string, FakeTransaction>();
  let corrections = 0;

  const find = (id: string) => posted.find((t) => t.id === id);
  const reversalOf = (id: string) =>
    posted.find((t) => t.reversesTransactionId === id)?.id ?? null;
  const view = (t: FakeTransaction) => ({
    id: t.id,
    kind: t.kind,
    bookingTime: t.bookingTime,
    paymentId: t.paymentId ?? null,
    reversesTransactionId: t.reversesTransactionId ?? null,
    correctionReason: t.correctionReason ?? null,
    correctionNote: t.correctionNote ?? null,
    entries: t.entries.map((e, i) => ({ ...e, entrySeq: i + 1 })),
    reversedByTransactionId: reversalOf(t.id),
  });
  const balanceAfter = (accountId: string, side: string, amount: number) => {
    const before =
      posted
        .flatMap((t) => t.entries)
        .filter((e) => e.accountId === accountId)
        .at(-1)?.balanceAfterPence ?? 0;
    // Customer Accounts are credit-normal; the Corrections Account is debit-normal.
    const normal = accountId === correctionsAccountId ? "DEBIT" : "CREDIT";
    return before + (side === normal ? amount : -amount);
  };
  const entry = (
    accountId: string,
    side: "DEBIT" | "CREDIT",
    amountPence: number,
  ): FakeTransactionEntry => ({
    accountId,
    side,
    amountPence,
    balanceAfterPence: balanceAfter(accountId, side, amountPence),
  });
  const opposite = (side: "DEBIT" | "CREDIT") =>
    side === "DEBIT" ? "CREDIT" : "DEBIT";

  function correct(body: CorrectionBody, key: string): Response {
    if (!body.reason || !body.note?.trim()) {
      return problem(
        400,
        "validation-failed",
        "A Correction needs a Correction Reason and a note",
      );
    }
    const corrected = body.transactionId ? find(body.transactionId) : undefined;
    if (body.transactionId && !corrected) {
      return problem(
        404,
        "not-found",
        `No Transaction ${body.transactionId} exists`,
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
    let entries: FakeTransactionEntry[];
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
      entries = corrected.entries.map((e) =>
        entry(e.accountId, opposite(e.side), e.amountPence),
      );
    } else {
      if (!body.accountId || !body.amountPence || !body.direction) {
        return problem(
          400,
          "validation-failed",
          "An Adjustment needs an accountId, an amountPence and a direction",
        );
      }
      entries = [
        entry(correctionsAccountId, opposite(body.direction), body.amountPence),
        entry(body.accountId, body.direction, body.amountPence),
      ];
    }
    corrections += 1;
    const correction: FakeTransaction = {
      id: `019a0000-0000-7000-a000-${String(corrections).padStart(12, "0")}`,
      kind: "CORRECTION",
      bookingTime: new Date().toISOString(),
      reversesTransactionId:
        body.kind === "REVERSING" ? corrected?.id : undefined,
      correctionReason: body.reason,
      correctionNote: body.note,
      entries,
    };
    posted.push(correction);
    byKey.set(key, correction);
    return json(201, view(correction));
  }

  function routes({ method, path, headers, body }: FakeRequest) {
    const [, transactionId] = path.match(/^\/v1\/transactions\/([^/]+)$/) ?? [];
    if (method === "GET" && transactionId) {
      const transaction = find(transactionId);
      return transaction
        ? json(200, view(transaction))
        : problem(404, "not-found", `No Transaction ${transactionId} exists`);
    }
    const [, paymentId] = path.match(/^\/v1\/payments\/([^/]+)$/) ?? [];
    if (method === "GET" && paymentId) {
      const payment = payments.find((p) => p.id === paymentId);
      if (!payment) return problem(404, "not-found", `No Payment ${paymentId}`);
      return json(200, {
        ...payment,
        rejectionReason: payment.rejectionReason ?? null,
        schemeReasonCode: null,
        transactionIds: posted
          .filter((t) => t.paymentId === paymentId)
          .map((t) => t.id),
      });
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
      const replayed = byKey.get(key);
      if (replayed) return json(200, view(replayed));
      return correct(body as CorrectionBody, key);
    }
    return undefined;
  }

  return { routes };
}
