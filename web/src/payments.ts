import { queryOptions } from "@tanstack/react-query";
import { csrfHeader, getJson, POLL_INTERVAL_MS, problemOf } from "./ledgerApi";

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

/** A Payment as the ledger has it now, with the Transactions it led to, oldest first. */
export type Payment = {
  id: string;
  type: "INTERNAL_TRANSFER" | "FASTER_PAYMENT";
  status: PaymentStatus;
  rejectionReason: RejectionReason | null;
  schemeReasonCode: string | null;
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

/** A Payment as it stands now, polled while it is Pending; Completed and Rejected are final. */
export const paymentQuery = (paymentId: string) =>
  queryOptions({
    queryKey: ["payments", paymentId],
    queryFn: () => getJson<Payment>(`/v1/payments/${paymentId}`, "Payment"),
    refetchInterval: (query) =>
      query.state.data?.status === "PENDING" ? POLL_INTERVAL_MS : false,
  });

export const PAYMENT_TYPE: Record<Payment["type"], string> = {
  INTERNAL_TRANSFER: "Internal Transfer",
  FASTER_PAYMENT: "Faster Payment",
};

/** Each Payment Status in the glossary's words. */
export const PAYMENT_STATUS: Record<PaymentStatus, string> = {
  PENDING: "Pending",
  COMPLETED: "Completed",
  REJECTED: "Rejected",
};

/** The MUI colour a Payment Status is shown in. */
export const PAYMENT_STATUS_COLOR: Record<
  PaymentStatus,
  "warning" | "success" | "error"
> = {
  PENDING: "warning",
  COMPLETED: "success",
  REJECTED: "error",
};

/** The Rejection Reason in the glossary's words, with the scheme's reason code if it gave one. */
export function rejectionReason({
  rejectionReason,
  schemeReasonCode,
}: Pick<Payment, "rejectionReason" | "schemeReasonCode">) {
  if (rejectionReason === "INSUFFICIENT_FUNDS") return "Insufficient Funds";
  if (rejectionReason === "SCHEME_REJECTED") {
    return schemeReasonCode
      ? `Scheme Rejected (${schemeReasonCode})`
      : "Scheme Rejected";
  }
  return null;
}
