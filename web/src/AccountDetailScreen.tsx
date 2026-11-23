import { Alert, Chip, Paper, Stack, Typography } from "@mui/material";
import { DatePicker } from "@mui/x-date-pickers/DatePicker";
import { DataGrid, type GridColDef } from "@mui/x-data-grid";
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import dayjs from "dayjs";
import { useEffect, useRef, useState } from "react";
import { asDate, bookingTimeRange } from "./accountSearch";
import { AsAtBalance } from "./AsAtBalance";
import {
  accountQuery,
  accountsQuery,
  balanceQuery,
  type StatementLine,
  statementQuery,
  type StatementRange,
} from "./ledgerApi";
import { formatPence } from "./money";

const route = getRouteApi("/accounts/$accountId");

/** One Account: its As-At Balance from the ledger and its Statement from the Read Model. */
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
      <AsAtBalance accountId={accountId} />
      <Statement accountId={accountId} />
    </Stack>
  );
}

function Statement({ accountId }: { accountId: string }) {
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const range = bookingTimeRange(search);
  const trailing = useTrailsLedger(accountId);

  return (
    <Stack spacing={1}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Typography variant="h5" component="h2">
          Statement
        </Typography>
        {trailing && <Chip label="updating…" color="warning" size="small" />}
      </Stack>
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
      <StatementGrid
        // A new range starts again from its first page.
        key={JSON.stringify(range)}
        accountId={accountId}
        range={range}
      />
    </Stack>
  );
}

/**
 * Whether the Read Model trails the ledger for this Account, as after a Payment the projector has
 * not applied yet: its Read Model Balance differs from the ledger's. When it catches up, the
 * Statement is fetched again at once rather than at the next poll.
 */
function useTrailsLedger(accountId: string) {
  const queryClient = useQueryClient();
  const readModel = useQuery(accountQuery(accountId));
  const ledger = useQuery(balanceQuery(accountId));
  const trailing =
    readModel.isSuccess &&
    ledger.isSuccess &&
    readModel.data.balancePence !== ledger.data.balancePence;
  const wasTrailing = useRef(false);
  useEffect(() => {
    if (wasTrailing.current && !trailing) {
      void queryClient.invalidateQueries({
        queryKey: ["accounts", accountId, "statement"],
      });
    }
    wasTrailing.current = trailing;
  }, [trailing, accountId, queryClient]);
  return trailing;
}

const PAGE_SIZE = 50;

function StatementGrid({
  accountId,
  range,
}: {
  accountId: string;
  range: StatementRange;
}) {
  const statement = useInfiniteQuery(statementQuery(accountId, range));
  const accounts = useQuery(accountsQuery());
  const [page, setPage] = useState(0);
  const pages = statement.data?.pages ?? [];
  // While the next page loads, the last one loaded still says whether there is one.
  const hasNextPage = page < pages.length - 1 || statement.hasNextPage;
  const nameOf = (id: string | null) =>
    id === null ? "" : (accounts.data?.find((a) => a.id === id)?.name ?? id);
  const columns = statementColumns(nameOf);

  return (
    <>
      {statement.isError && (
        <Alert severity="error">The Statement is unavailable.</Alert>
      )}
      <Paper>
        <DataGrid
          aria-label="Statement"
          rows={pages[page]?.lines ?? []}
          getRowId={(line) => line.entrySeq}
          columns={columns}
          loading={statement.isPending || statement.isFetchingNextPage}
          paginationMode="server"
          paginationModel={{ page, pageSize: PAGE_SIZE }}
          pageSizeOptions={[PAGE_SIZE]}
          rowCount={-1}
          paginationMeta={{ hasNextPage }}
          onPaginationModelChange={(model) => {
            if (model.page >= pages.length) void statement.fetchNextPage();
            setPage(model.page);
          }}
          disableColumnFilter
          disableColumnSorting
          disableRowSelectionOnClick
        />
      </Paper>
    </>
  );
}

function statementColumns(
  nameOf: (id: string | null) => string,
): GridColDef<StatementLine>[] {
  return [
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
}
