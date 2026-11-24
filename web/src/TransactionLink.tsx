import { useQuery } from "@tanstack/react-query";
import dayjs from "dayjs";
import { RouterLink } from "./RouterLink";
import { type TransactionKind, transactionQuery } from "./transactionApi";

export const TRANSACTION_KIND: Record<TransactionKind, string> = {
  PAYMENT: "Payment",
  REVERSAL: "Reversal",
  FUNDING: "Funding",
  CORRECTION: "Correction",
};

export const formatBookingTime = (bookingTime: string) =>
  dayjs(bookingTime).format("D MMM YYYY, HH:mm");

/** A link to a Transaction, named by its kind and Booking Time once they are known. */
export function TransactionLink({ transactionId }: { transactionId: string }) {
  const transaction = useQuery(transactionQuery(transactionId));
  return (
    <RouterLink to="/transactions/$transactionId" params={{ transactionId }}>
      {transaction.data
        ? `${TRANSACTION_KIND[transaction.data.kind]}, ${formatBookingTime(
            transaction.data.bookingTime,
          )}`
        : transactionId}
    </RouterLink>
  );
}
