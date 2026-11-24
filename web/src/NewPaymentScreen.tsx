import {
  Alert,
  Box,
  Button,
  Chip,
  FormControl,
  FormControlLabel,
  FormLabel,
  Paper,
  Radio,
  RadioGroup,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useId, useState } from "react";
import {
  accountsQuery,
  makePayment,
  type Payment,
  type PaymentRequest,
  paymentQuery,
} from "./ledgerApi";
import { parsePounds } from "./money";

type PaymentType = Payment["type"];

/** What the Customer has typed into the form. */
type Form = {
  type: PaymentType;
  from: string;
  to: string;
  sortCode: string;
  accountNumber: string;
  name: string;
  amount: string;
};

const EMPTY_FORM: Form = {
  type: "INTERNAL_TRANSFER",
  from: "",
  to: "",
  sortCode: "",
  accountNumber: "",
  name: "",
  amount: "",
};

/** A Customer makes an Internal Transfer or a Faster Payment and watches its Payment Status. */
export function NewPaymentScreen() {
  const queryClient = useQueryClient();
  const accounts = useQuery(accountsQuery());
  const customerAccounts =
    accounts.data?.filter((a) => a.kind === "CUSTOMER") ?? [];
  const [form, setForm] = useState(EMPTY_FORM);
  const payment = useMutation({
    mutationFn: ({
      request,
      idempotencyKey,
    }: {
      request: PaymentRequest;
      idempotencyKey: string;
    }) => makePayment(request, idempotencyKey),
    onSuccess: (made) =>
      queryClient.setQueryData(paymentQuery(made.id).queryKey, made),
  });

  const field = (name: keyof Form) => ({
    value: form[name],
    onChange: (e: { target: { value: string } }) =>
      setForm((prev) => ({ ...prev, [name]: e.target.value })),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const amountPence = parsePounds(form.amount);
    if (amountPence === undefined) return;
    payment.mutate({
      request: {
        debtorAccountId: form.from,
        payee:
          form.type === "INTERNAL_TRANSFER"
            ? { accountId: form.to }
            : {
                sortCode: form.sortCode,
                accountNumber: form.accountNumber,
                name: form.name,
              },
        amountPence,
      },
      idempotencyKey: crypto.randomUUID(),
    });
  }

  const accountOptions = [
    <option key="" value="" disabled />,
    ...customerAccounts.map((a) => (
      <option key={a.id} value={a.id}>
        {a.name}
      </option>
    )),
  ];

  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">
        New Payment
      </Typography>
      <Paper component="form" onSubmit={submit} sx={{ p: 3 }}>
        <Stack spacing={2}>
          <FormControl>
            <FormLabel id="payment-type">Payment type</FormLabel>
            <RadioGroup row aria-labelledby="payment-type" {...field("type")}>
              <FormControlLabel
                value="INTERNAL_TRANSFER"
                control={<Radio />}
                label="Internal Transfer"
              />
              <FormControlLabel
                value="FASTER_PAYMENT"
                control={<Radio />}
                label="Faster Payment"
              />
            </RadioGroup>
          </FormControl>
          <TextField
            select
            label="From"
            {...field("from")}
            slotProps={{ select: { native: true } }}
          >
            {accountOptions}
          </TextField>
          {form.type === "INTERNAL_TRANSFER" ? (
            <TextField
              select
              label="To"
              {...field("to")}
              slotProps={{ select: { native: true } }}
            >
              {accountOptions}
            </TextField>
          ) : (
            <>
              <TextField
                label="Sort code"
                {...field("sortCode")}
                slotProps={{ htmlInput: { inputMode: "numeric" } }}
              />
              <TextField
                label="Account number"
                {...field("accountNumber")}
                slotProps={{ htmlInput: { inputMode: "numeric" } }}
              />
              <TextField label="Name" {...field("name")} />
            </>
          )}
          <TextField
            label="Amount (£)"
            {...field("amount")}
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
      {payment.isSuccess && <PaymentStatus paymentId={payment.data.id} />}
    </Stack>
  );
}

const STATUS: Record<
  Payment["status"],
  { label: string; color: "warning" | "success" | "error" }
> = {
  PENDING: { label: "Pending", color: "warning" },
  COMPLETED: { label: "Completed", color: "success" },
  REJECTED: { label: "Rejected", color: "error" },
};

/** The Rejection Reason in the glossary's words, with the scheme's reason code if it gave one. */
function rejectionReason({ rejectionReason, schemeReasonCode }: Payment) {
  if (rejectionReason === "INSUFFICIENT_FUNDS") return "Insufficient Funds";
  if (rejectionReason === "SCHEME_REJECTED") {
    return schemeReasonCode
      ? `Scheme Rejected (${schemeReasonCode})`
      : "Scheme Rejected";
  }
  return null;
}

/** The Payment's live Payment Status, polled until it is final. */
function PaymentStatus({ paymentId }: { paymentId: string }) {
  const heading = useId();
  const payment = useQuery(paymentQuery(paymentId));
  return (
    <Paper component="section" aria-labelledby={heading} sx={{ p: 3 }}>
      <Stack spacing={1}>
        <Typography variant="h5" component="h2" id={heading}>
          Payment Status
        </Typography>
        {payment.isSuccess && (
          <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
            <Chip
              label={STATUS[payment.data.status].label}
              color={STATUS[payment.data.status].color}
            />
            {payment.data.status === "REJECTED" && (
              <Typography>{rejectionReason(payment.data)}</Typography>
            )}
          </Stack>
        )}
        {payment.isError && (
          <Alert severity="error">The Payment Status is unavailable.</Alert>
        )}
      </Stack>
    </Paper>
  );
}
