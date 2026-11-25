import type { Payment } from "./ledgerApi";

/** Each Payment Status in the glossary's words. */
export const PAYMENT_STATUS: Record<Payment["status"], string> = {
  PENDING: "Pending",
  COMPLETED: "Completed",
  REJECTED: "Rejected",
};

/** The Rejection Reason in the glossary's words, with the scheme's reason code if it gave one. */
export function rejectionReason({
  rejectionReason,
  schemeReasonCode,
}: Payment) {
  if (rejectionReason === "INSUFFICIENT_FUNDS") return "Insufficient Funds";
  if (rejectionReason === "SCHEME_REJECTED") {
    return schemeReasonCode
      ? `Scheme Rejected (${schemeReasonCode})`
      : "Scheme Rejected";
  }
  return null;
}
