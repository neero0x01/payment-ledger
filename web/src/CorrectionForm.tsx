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
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import {
  type CorrectionReason,
  type CorrectionRequest,
  LedgerProblem,
  postCorrection,
  type Transaction,
} from "./transactionApi";

type Kind = CorrectionRequest["kind"];

export const CORRECTION_REASON: Record<CorrectionReason, string> = {
  DUPLICATE_POSTING: "Duplicate Posting",
  WRONG_AMOUNT: "Wrong Amount",
  WRONG_ACCOUNT: "Wrong Account",
  GOODWILL: "Goodwill",
  OTHER: "Other",
};

/** What a refused Correction means for the Operator, by problem type. */
function refusal(error: Error): string {
  if (error instanceof LedgerProblem) {
    switch (error.type) {
      case "/problems/already-reversed":
        return "This Transaction has already been reversed, so it can't be reversed again.";
      case "/problems/payment-pending":
        return "This Transaction's Payment is still Pending. It can be corrected once the scheme has decided.";
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
  const correction = useMutation({
    mutationFn: ({
      request,
      idempotencyKey,
    }: {
      request: CorrectionRequest;
      idempotencyKey: string;
    }) => postCorrection(request, idempotencyKey),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ["transactions"] }),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!reason) return;
    correction.mutate({
      request: {
        kind: "REVERSING",
        transactionId: transaction.id,
        reason,
        note,
      },
      idempotencyKey: crypto.randomUUID(),
    });
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
