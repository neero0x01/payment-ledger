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
import { type FormEvent, useId, useRef, useState } from "react";
import {
  accountsQuery,
  makePayment,
  type Payment,
  type PaymentRequest,
  paymentQuery,
  ProblemError,
} from "./ledgerApi";
import {
  EMPTY_FORM,
  type NewPaymentForm,
  problemsFromLedger,
  validate,
} from "./newPaymentForm";
import { PAYMENT_STATUS, rejectionReason } from "./paymentStatus";

/** A Customer makes an Internal Transfer or a Faster Payment and watches its Payment Status. */
export function NewPaymentScreen() {
  const queryClient = useQueryClient();
  const accounts = useQuery(accountsQuery());
  const customerAccounts =
    accounts.data?.filter((a) => a.kind === "CUSTOMER") ?? [];
  const [form, setForm] = useState(EMPTY_FORM);
  // Problems show once the Customer first tries to pay, then follow the form as it is fixed.
  const [triedToPay, setTriedToPay] = useState(false);
  const problems = triedToPay ? (validate(form).problems ?? {}) : {};
  // One Idempotency-Key per submission of the form as it stands: a double-click, or paying again
  // after a lost response, resends it and gets the same Payment back. Editing the form starts a new
  // submission.
  const idempotencyKey = useRef<string | null>(null);
  const sending = useRef(false);
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
    onSettled: () => {
      sending.current = false;
    },
  });

  // A problem the ledger refused the Payment with shows beside the field it names, until the form
  // is edited; one that names no field of the form shows above Pay.
  const refusal =
    payment.error instanceof ProblemError ? payment.error : undefined;
  const ledgerProblems = refusal
    ? problemsFromLedger(refusal.field, refusal.detail)
    : {};
  const refusalBesideField = Object.keys(ledgerProblems).length > 0;

  const field = (name: keyof NewPaymentForm) => ({
    value: form[name],
    onChange: (e: { target: { value: string } }) => {
      idempotencyKey.current = null;
      if (payment.isError) payment.reset();
      setForm((prev) => ({ ...prev, [name]: e.target.value }));
    },
    error: (problems[name] ?? ledgerProblems[name]) !== undefined,
    helperText: problems[name] ?? ledgerProblems[name],
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setTriedToPay(true);
    const { request } = validate(form);
    if (!request || sending.current) return;
    sending.current = true;
    idempotencyKey.current ??= crypto.randomUUID();
    payment.mutate({ request, idempotencyKey: idempotencyKey.current });
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
      <Paper
        component="form"
        aria-label="New Payment"
        noValidate
        onSubmit={submit}
        sx={{ p: 3 }}
      >
        <Stack spacing={2}>
          <FormControl>
            <FormLabel id="payment-type">Payment type</FormLabel>
            <RadioGroup
              row
              aria-labelledby="payment-type"
              value={form.type}
              onChange={field("type").onChange}
            >
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
          {payment.isError && !refusalBesideField && (
            <Alert severity="error">
              {refusal
                ? `The ledger refused the Payment: ${refusal.detail}`
                : `The Payment failed: ${payment.error.message}. Paying again resends the same Payment.`}
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

const STATUS_COLOR: Record<Payment["status"], "warning" | "success" | "error"> =
  {
    PENDING: "warning",
    COMPLETED: "success",
    REJECTED: "error",
  };

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
              label={PAYMENT_STATUS[payment.data.status]}
              color={STATUS_COLOR[payment.data.status]}
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
