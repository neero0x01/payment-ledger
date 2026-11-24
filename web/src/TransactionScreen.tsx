import {
  Alert,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import { useQueries, useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { accountQuery } from "./ledgerApi";
import { formatPence } from "./money";
import { RouterLink } from "./RouterLink";
import {
  type CorrectionReason,
  type Transaction,
  transactionQuery,
} from "./transactionApi";
import {
  formatBookingTime,
  TRANSACTION_KIND,
  TransactionLink,
} from "./TransactionLink";

const route = getRouteApi("/transactions/$transactionId");

export const CORRECTION_REASON: Record<CorrectionReason, string> = {
  DUPLICATE_POSTING: "Duplicate Posting",
  WRONG_AMOUNT: "Wrong Amount",
  WRONG_ACCOUNT: "Wrong Account",
  GOODWILL: "Goodwill",
  OTHER: "Other",
};

/** One Transaction as the ledger recorded it, for an Operator to investigate and correct. */
export function TransactionScreen() {
  const { transactionId } = route.useParams();
  const transaction = useQuery(transactionQuery(transactionId));

  if (transaction.isError) {
    return <Alert severity="error">The Transaction is unavailable.</Alert>;
  }
  if (!transaction.data) return <Typography>loading…</Typography>;
  const t = transaction.data;
  return (
    <Stack spacing={3}>
      <Stack spacing={1}>
        <Typography variant="h4" component="h1">
          {TRANSACTION_KIND[t.kind]} Transaction
        </Typography>
        <Typography color="text.secondary">
          Booked {formatBookingTime(t.bookingTime)}
        </Typography>
        {t.paymentId && (
          <Typography>
            Part of a{" "}
            <RouterLink
              to="/payments/$paymentId"
              params={{ paymentId: t.paymentId }}
            >
              Payment
            </RouterLink>
          </Typography>
        )}
        {t.reversesTransactionId && (
          <Typography>
            Reverses <TransactionLink transactionId={t.reversesTransactionId} />
          </Typography>
        )}
        {t.reversedByTransactionId && (
          <Typography>
            Reversed by{" "}
            <TransactionLink transactionId={t.reversedByTransactionId} />
          </Typography>
        )}
        {t.correctionReason && (
          <Typography>
            Correction Reason:{" "}
            <span>{CORRECTION_REASON[t.correctionReason]}</span>
          </Typography>
        )}
        {t.correctionNote && (
          <Typography>
            Note: <span>{t.correctionNote}</span>
          </Typography>
        )}
      </Stack>
      <Entries transaction={t} />
    </Stack>
  );
}

function Entries({ transaction }: { transaction: Transaction }) {
  const accounts = useQueries({
    queries: transaction.entries.map((e) => accountQuery(e.accountId)),
  });
  const nameOf = (accountId: string) =>
    accounts.find((a) => a.data?.id === accountId)?.data?.name ?? accountId;
  return (
    <TableContainer component={Paper}>
      <Table aria-label="Entries">
        <TableHead>
          <TableRow>
            <TableCell>Account</TableCell>
            <TableCell>Side</TableCell>
            <TableCell align="right">Amount</TableCell>
            <TableCell align="right">Balance after</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {transaction.entries.map((entry) => (
            <TableRow key={entry.accountId + entry.side}>
              <TableCell>
                <RouterLink
                  to="/accounts/$accountId"
                  params={{ accountId: entry.accountId }}
                >
                  {nameOf(entry.accountId)}
                </RouterLink>
              </TableCell>
              <TableCell>
                {entry.side === "DEBIT" ? "Debit" : "Credit"}
              </TableCell>
              <TableCell align="right">
                {formatPence(entry.amountPence)}
              </TableCell>
              <TableCell align="right">
                {formatPence(entry.balanceAfterPence)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}
