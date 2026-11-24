import {
  Alert,
  Box,
  Button,
  Paper,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useMutation, useQuery } from "@tanstack/react-query";
import { type FormEvent, useId, useState } from "react";
import {
  accountsQuery,
  makePayment,
  type Payment,
  type PaymentRequest,
} from "./ledgerApi";
import { parsePounds } from "./money";

/** A Customer makes an Internal Transfer and watches its Payment Status. */
export function NewPaymentScreen() {
  const accounts = useQuery(accountsQuery());
  const customerAccounts =
    accounts.data?.filter((a) => a.kind === "CUSTOMER") ?? [];
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [amount, setAmount] = useState("");
  const payment = useMutation({
    mutationFn: ({
      request,
      idempotencyKey,
    }: {
      request: PaymentRequest;
      idempotencyKey: string;
    }) => makePayment(request, idempotencyKey),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const amountPence = parsePounds(amount);
    if (amountPence === undefined) return;
    payment.mutate({
      request: {
        debtorAccountId: from,
        payee: { accountId: to },
        amountPence,
      },
      idempotencyKey: crypto.randomUUID(),
    });
  }

  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">
        New Payment
      </Typography>
      <Paper component="form" onSubmit={submit} sx={{ p: 3 }}>
        <Stack spacing={2}>
          <TextField
            select
            label="From"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            slotProps={{ select: { native: true } }}
          >
            <option value="" disabled />
            {customerAccounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </TextField>
          <TextField
            select
            label="To"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            slotProps={{ select: { native: true } }}
          >
            <option value="" disabled />
            {customerAccounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </TextField>
          <TextField
            label="Amount (£)"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            slotProps={{ htmlInput: { inputMode: "decimal" } }}
          />
          {payment.isError && (
            <Alert severity="error">
              The Payment failed: {payment.error.message}
            </Alert>
          )}
          <Box>
            <Button
              type="submit"
              variant="contained"
              disabled={payment.isPending}
            >
              Pay
            </Button>
          </Box>
        </Stack>
      </Paper>
      {payment.isSuccess && <PaymentStatus payment={payment.data} />}
    </Stack>
  );
}

const STATUS_LABEL: Record<Payment["status"], string> = {
  PENDING: "Pending",
  COMPLETED: "Completed",
  REJECTED: "Rejected",
};

function PaymentStatus({ payment }: { payment: Payment }) {
  const heading = useId();
  return (
    <Paper component="section" aria-labelledby={heading} sx={{ p: 3 }}>
      <Typography variant="h5" component="h2" id={heading}>
        Payment Status
      </Typography>
      <Typography>{STATUS_LABEL[payment.status]}</Typography>
    </Paper>
  );
}
