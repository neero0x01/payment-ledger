import { Alert, List, ListItem, Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { formatPence } from "./money";
import { PAYMENT_STATUS, rejectionReason } from "./paymentStatus";
import { paymentQuery } from "./transactionApi";
import { TransactionLink } from "./TransactionLink";

const route = getRouteApi("/payments/$paymentId");

const PAYMENT_TYPE = {
  INTERNAL_TRANSFER: "Internal Transfer",
  FASTER_PAYMENT: "Faster Payment",
} as const;

/** One Payment as the ledger has it now, with the Transactions it led to. */
export function PaymentScreen() {
  const { paymentId } = route.useParams();
  const payment = useQuery(paymentQuery(paymentId));

  if (payment.isError) {
    return <Alert severity="error">The Payment is unavailable.</Alert>;
  }
  if (!payment.data) return <Typography>loading…</Typography>;
  const p = payment.data;
  return (
    <Stack spacing={2}>
      <Typography variant="h4" component="h1">
        {PAYMENT_TYPE[p.type]}
      </Typography>
      <Typography>{formatPence(p.amountPence)}</Typography>
      <Typography>{PAYMENT_STATUS[p.status]}</Typography>
      {p.rejectionReason && (
        <Typography>Rejection Reason: {rejectionReason(p)}</Typography>
      )}
      <Typography variant="h5" component="h2">
        Transactions
      </Typography>
      <List>
        {p.transactionIds.map((id) => (
          <ListItem key={id}>
            <TransactionLink transactionId={id} />
          </ListItem>
        ))}
      </List>
    </Stack>
  );
}
