import { Alert, Paper, Stack, Typography } from "@mui/material";
import { DatePicker } from "@mui/x-date-pickers/DatePicker";
import { DataGrid, type GridColDef } from "@mui/x-data-grid";
import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import dayjs from "dayjs";
import { asDate, bookingTimeRange } from "./accountSearch";
import {
  accountQuery,
  accountsQuery,
  type StatementLine,
  statementQuery,
} from "./ledgerApi";
import { formatPence } from "./money";

const route = getRouteApi("/accounts/$accountId");

/** One Account: its Statement from the Read Model. */
export function AccountDetailScreen() {
  const { accountId } = route.useParams();
  const account = useQuery(accountQuery(accountId));
  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">
        {account.data?.name ?? "Account"}
      </Typography>
      {account.isError && (
        <Alert severity="error">The Account is unavailable.</Alert>
      )}
      <Statement accountId={accountId} />
    </Stack>
  );
}

function Statement({ accountId }: { accountId: string }) {
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const statement = useQuery(
    statementQuery(accountId, bookingTimeRange(search)),
  );
  const accounts = useQuery(accountsQuery());
  const nameOf = (id: string | null) =>
    id === null ? "" : (accounts.data?.find((a) => a.id === id)?.name ?? id);

  const columns: GridColDef<StatementLine>[] = [
    {
      field: "bookingTime",
      headerName: "Booking Time",
      flex: 1,
      valueFormatter: (value: string) =>
        dayjs(value).format("D MMM YYYY, HH:mm"),
    },
    {
      field: "side",
      headerName: "Side",
      width: 100,
      valueFormatter: (value: StatementLine["side"]) =>
        value === "DEBIT" ? "Debit" : "Credit",
    },
    {
      field: "amountPence",
      headerName: "Amount",
      type: "number",
      width: 130,
      valueFormatter: (value: number) => formatPence(value),
    },
    {
      field: "counterpartyAccountId",
      headerName: "Counterparty",
      flex: 1,
      valueFormatter: (value: string | null) => nameOf(value),
    },
    {
      field: "balanceAfterPence",
      headerName: "Balance after",
      type: "number",
      width: 150,
      valueFormatter: (value: number) => formatPence(value),
    },
  ];

  return (
    <Stack spacing={1}>
      <Typography variant="h5" component="h2">
        Statement
      </Typography>
      <Stack direction="row" spacing={2}>
        <DatePicker
          label="From"
          value={search.from ? dayjs(search.from) : null}
          maxDate={search.to ? dayjs(search.to) : undefined}
          onChange={(value) => {
            if (value && !value.isValid()) return;
            void navigate({
              search: (prev) => ({ ...prev, from: asDate(value) }),
            });
          }}
        />
        <DatePicker
          label="To"
          value={search.to ? dayjs(search.to) : null}
          minDate={search.from ? dayjs(search.from) : undefined}
          onChange={(value) => {
            if (value && !value.isValid()) return;
            void navigate({
              search: (prev) => ({ ...prev, to: asDate(value) }),
            });
          }}
        />
      </Stack>
      {statement.isError && (
        <Alert severity="error">The Statement is unavailable.</Alert>
      )}
      <Paper>
        <DataGrid
          aria-label="Statement"
          rows={statement.data?.lines ?? []}
          getRowId={(line) => line.entrySeq}
          columns={columns}
          loading={statement.isPending}
          disableColumnFilter
          disableColumnSorting
          disableRowSelectionOnClick
          hideFooter
        />
      </Paper>
    </Stack>
  );
}
