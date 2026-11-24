import {
  Alert,
  Box,
  Button,
  FormControl,
  FormControlLabel,
  FormLabel,
  Radio,
  RadioGroup,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useMutation, useQueries, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useRef, useState } from "react";
import { accountQuery, ProblemError } from "./ledgerApi";
import { parsePounds } from "./money";
import {
  type CorrectionReason,
  type CorrectionRequest,
  postCorrection,
  type Side,
  type Transaction,
} from "./transactionApi";
import { TransactionLink } from "./TransactionLink";

type Kind = CorrectionRequest["kind"];

/** The Customer Accounts the Transaction touches: the ones an Adjustment of it could move money on. */
function useCustomerAccountsOf(transaction: Transaction) {
  const accounts = useQueries({
    queries: transaction.entries.map((e) => accountQuery(e.accountId)),
  });
  return accounts
    .flatMap((account) => (account.data ? [account.data] : []))
    .filter((account) => account.kind === "CUSTOMER");
}

export const CORRECTION_REASON: Record<CorrectionReason, string> = {
  DUPLICATE_POSTING: "Duplicate Posting",
  WRONG_AMOUNT: "Wrong Amount",
  WRONG_ACCOUNT: "Wrong Account",
  GOODWILL: "Goodwill",
  OTHER: "Other",
};

/** What a refused Correction means for the Operator, by problem type. */
function refusal(error: Error): string {
  if (error instanceof ProblemError) {
    switch (error.type) {
      case "/problems/already-reversed":
        return "This Transaction has already been reversed, so it can't be reversed again.";
      case "/problems/payment-pending":
        return "This Transaction's Payment is still Pending. It can be corrected once the scheme has decided.";
      case "/problems/seed-data-read-only":
        return "This Transaction is seeded data, which is read-only. No Correction can touch it.";
      case "/problems/validation-failed":
        return error.detail;
    }
  }
  return `The Correction failed: ${error.message}`;
}

/** Posts a Correction of the Transaction: a Reversing Correction or an Adjustment. */
export function CorrectionForm({ transaction }: { transaction: Transaction }) {
  const queryClient = useQueryClient();
  const [kind, setKind] = useState<Kind>("REVERSING");
  const [reason, setReason] = useState<CorrectionReason | "">("");
  const [note, setNote] = useState("");
  const customerAccounts = useCustomerAccountsOf(transaction);
  const [chosenAccountId, setAccountId] = useState("");
  const accountId = chosenAccountId || customerAccounts[0]?.id || "";
  const [amount, setAmount] = useState("");
  const [amountInvalid, setAmountInvalid] = useState(false);
  const [direction, setDirection] = useState<Side>("CREDIT");
  /**
   * The submission not yet known to be posted, with its Idempotency-Key. Sending the same request
   * again, as a retry or a double-click, reuses the key; a changed request or a posted one starts a
   * new submission.
   */
  const submission = useRef<{ request: string; key: string } | null>(null);
  const correction = useMutation({
    mutationFn: ({
      request,
      idempotencyKey,
    }: {
      request: CorrectionRequest;
      idempotencyKey: string;
    }) => postCorrection(request, idempotencyKey),
    onSuccess: () => {
      // The submission is done: the next Correction is filled in afresh and gets a new key.
      submission.current = null;
      setReason("");
      setNote("");
      setAmount("");
      return queryClient.invalidateQueries({ queryKey: ["transactions"] });
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!reason) return;
    let request: CorrectionRequest;
    if (kind === "REVERSING") {
      request = { kind, transactionId: transaction.id, reason, note };
    } else {
      const amountPence = parsePounds(amount);
      setAmountInvalid(!amountPence);
      if (!amountPence) return;
      request = {
        kind,
        transactionId: transaction.id,
        reason,
        note,
        accountId,
        amountPence,
        direction,
      };
    }
    const body = JSON.stringify(request);
    if (submission.current?.request !== body) {
      submission.current = { request: body, key: crypto.randomUUID() };
    }
    correction.mutate({ request, idempotencyKey: submission.current.key });
  }

  return (
    <Box component="form" aria-label="Correction" onSubmit={submit}>
      <Stack spacing={2}>
        <Typography variant="h5" component="h2">
          Correct this Transaction
        </Typography>
        <FormControl>
          <FormLabel id="correction-kind">Kind</FormLabel>
          <RadioGroup
            row
            aria-labelledby="correction-kind"
            value={kind}
            onChange={(e) => setKind(e.target.value as Kind)}
          >
            <FormControlLabel
              value="REVERSING"
              control={<Radio />}
              label="Reversing Correction"
            />
            <FormControlLabel
              value="ADJUSTMENT"
              control={<Radio />}
              label="Adjustment"
            />
          </RadioGroup>
        </FormControl>
        {kind === "ADJUSTMENT" && (
          <>
            <TextField
              select
              required
              id="adjustment-account"
              label="Customer Account"
              value={accountId}
              onChange={(e) => setAccountId(e.target.value)}
              slotProps={{
                select: { native: true },
                inputLabel: { shrink: true },
              }}
            >
              {customerAccounts.map((account) => (
                <option key={account.id} value={account.id}>
                  {account.name}
                </option>
              ))}
            </TextField>
            <TextField
              required
              id="adjustment-amount"
              label="Amount (£)"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              error={amountInvalid}
              helperText={
                amountInvalid
                  ? "Enter an amount in pounds, such as 5.00."
                  : undefined
              }
              slotProps={{ htmlInput: { inputMode: "decimal" } }}
            />
            <TextField
              select
              required
              id="adjustment-direction"
              label="Direction"
              value={direction}
              onChange={(e) => setDirection(e.target.value as Side)}
              slotProps={{
                select: { native: true },
                inputLabel: { shrink: true },
              }}
            >
              <option value="CREDIT">Credit the Customer Account</option>
              <option value="DEBIT">Debit the Customer Account</option>
            </TextField>
          </>
        )}
        <TextField
          select
          required
          id="correction-reason"
          label="Correction Reason"
          value={reason}
          onChange={(e) => setReason(e.target.value as CorrectionReason)}
          slotProps={{ select: { native: true }, inputLabel: { shrink: true } }}
        >
          <option value="" disabled>
            Choose a reason
          </option>
          {Object.entries(CORRECTION_REASON).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </TextField>
        <TextField
          required
          multiline
          id="correction-note"
          label="Note"
          value={note}
          onChange={(e) => setNote(e.target.value)}
        />
        {correction.isSuccess && (
          <Alert severity="success">
            Posted <TransactionLink transactionId={correction.data.id} />
          </Alert>
        )}
        {correction.isError && (
          <Alert severity="error">{refusal(correction.error)}</Alert>
        )}
        <Box>
          <Button
            type="submit"
            variant="contained"
            disabled={correction.isPending}
          >
            Post Correction
          </Button>
        </Box>
      </Stack>
    </Box>
  );
}
