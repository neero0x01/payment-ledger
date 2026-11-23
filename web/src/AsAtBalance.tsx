import { Alert, Paper, Stack, Typography } from "@mui/material";
import { DateTimePicker } from "@mui/x-date-pickers/DateTimePicker";
import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import dayjs from "dayjs";
import { useId } from "react";
import { balanceQuery } from "./ledgerApi";
import { formatPence } from "./money";

const route = getRouteApi("/accounts/$accountId");

/** The Account's As-At Balance at a moment chosen with a picker and kept in the URL. */
export function AsAtBalance({ accountId }: { accountId: string }) {
  const { asAt } = route.useSearch();
  const navigate = route.useNavigate();
  const heading = useId();
  const balance = useQuery({
    ...balanceQuery(accountId, asAt),
    enabled: asAt !== undefined,
  });

  return (
    <Paper component="section" aria-labelledby={heading} sx={{ p: 2 }}>
      <Stack spacing={2}>
        <Typography variant="h5" component="h2" id={heading}>
          As-At Balance
        </Typography>
        <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
          <DateTimePicker
            label="As at"
            value={asAt ? dayjs(asAt) : null}
            disableFuture
            onChange={(value) => {
              if (value && !value.isValid()) return;
              void navigate({
                search: (prev) => ({
                  ...prev,
                  asAt: value ? value.toISOString() : undefined,
                }),
              });
            }}
          />
          {asAt === undefined && (
            <Typography color="text.secondary">
              Choose a moment to see the Balance then.
            </Typography>
          )}
          {balance.isSuccess && (
            <Typography variant="h6" component="p">
              {formatPence(balance.data.balancePence)}
            </Typography>
          )}
        </Stack>
        {balance.isError && (
          <Alert severity="error">The As-At Balance is unavailable.</Alert>
        )}
      </Stack>
    </Paper>
  );
}
