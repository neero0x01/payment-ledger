import { queryOptions } from "@tanstack/react-query";
import {
  csrfHeader,
  getJson,
  type Payment,
  POLL_INTERVAL_MS,
  problemOf,
} from "./ledgerApi";

export type TransactionKind = "PAYMENT" | "REVERSAL" | "FUNDING" | "CORRECTION";

export type CorrectionReason =
  "DUPLICATE_POSTING" | "WRONG_AMOUNT" | "WRONG_ACCOUNT" | "GOODWILL" | "OTHER";

export type Side = "DEBIT" | "CREDIT";

export type TransactionEntry = {
  accountId: string;
  side: Side;
  amountPence: number;
  entrySeq: number;
  balanceAfterPence: number;
};

/** A Transaction as the ledger recorded it, with the Reversal or Reversing Correction of it, if any. */
export type Transaction = {
  id: string;
  kind: TransactionKind;
  bookingTime: string;
  paymentId: string | null;
  reversesTransactionId: string | null;
  correctionReason: CorrectionReason | null;
  correctionNote: string | null;
  entries: TransactionEntry[];
  reversedByTransactionId: string | null;
};

/** A Payment as the ledger has it now, with the Transactions it led to, oldest first. */
export type PaymentDetail = Payment & {
  debtorAccountId: string;
  payee: {
    accountId: string | null;
    sortCode: string | null;
    accountNumber: string | null;
    name: string | null;
  };
  amountPence: number;
  transactionIds: string[];
};

export const transactionQuery = (transactionId: string) =>
  queryOptions({
    queryKey: ["transactions", transactionId],
    queryFn: () =>
      getJson<Transaction>(`/v1/transactions/${transactionId}`, "Transaction"),
  });

export const paymentQuery = (paymentId: string) =>
  queryOptions({
    queryKey: ["payments", paymentId],
    queryFn: () =>
      getJson<PaymentDetail>(`/v1/payments/${paymentId}`, "Payment"),
    // A Pending Payment's status changes when the scheme decides.
    refetchInterval: POLL_INTERVAL_MS,
  });

/**
 * A Correction as the Operator asks for it. A Reversing Correction mirrors the Transaction; an
 * Adjustment posts the amount to the Customer Account on the direction's side.
 */
export type CorrectionRequest =
  | {
      kind: "REVERSING";
      transactionId: string;
      reason: CorrectionReason;
      note: string;
    }
  | {
      kind: "ADJUSTMENT";
      transactionId: string;
      reason: CorrectionReason;
      note: string;
      accountId: string;
      amountPence: number;
      direction: Side;
    };

/** Posts a Correction. The Idempotency-Key belongs to one Operator submission. */
export async function postCorrection(
  correction: CorrectionRequest,
  idempotencyKey: string,
): Promise<Transaction> {
  const response = await fetch("/v1/corrections", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
      ...csrfHeader(),
    },
    body: JSON.stringify(correction),
  });
  if (!response.ok) throw await problemOf(response);
  return response.json();
}
