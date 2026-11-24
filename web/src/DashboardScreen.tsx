import {
  Alert,
  Card,
  CardContent,
  Chip,
  Grid,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import dayjs from "dayjs";
import { type ReactNode, useId } from "react";
import { accountsQuery, type Dashboard, dashboardQuery } from "./ledgerApi";
import { formatPence } from "./money";
import { RouterLink } from "./RouterLink";

const seconds = (value: number | null) =>
  value === null ? "unknown" : `${Number(value.toFixed(1))} s`;

/** One figure on the dashboard, a region named by its label. */
function Figure({ label, children }: { label: string; children: ReactNode }) {
  const id = useId();
  return (
    <Grid size={{ xs: 12, sm: 6, md: 3 }}>
      <Card component="section" aria-labelledby={id} sx={{ height: "100%" }}>
        <CardContent>
          <Typography id={id} variant="overline" color="text.secondary">
            {label}
          </Typography>
          {children}
        </CardContent>
      </Card>
    </Grid>
  );
}

function Value({ children }: { children: ReactNode }) {
  return (
    <Typography variant="h4" component="p">
      {children}
    </Typography>
  );
}

function BalancedStatus({ check }: { check: Dashboard["balancedCheck"] }) {
  if (check.balanced === null || check.lastRunAt === null) {
    return <Chip label="Not checked yet" />;
  }
  return (
    <>
      {check.balanced ? (
        <Chip label="Balanced" color="success" />
      ) : (
        <Chip
          label={`Not balanced: ${formatPence(check.imbalancePence ?? 0)} drift`}
          color="error"
        />
      )}
      <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
        checked {dayjs(check.lastRunAt).format("D MMM YYYY, HH:mm:ss")}
      </Typography>
    </>
  );
}

function StuckPayments({ dashboard }: { dashboard: Dashboard }) {
  const accounts = useQuery(accountsQuery());
  const nameOf = (accountId: string) =>
    accounts.data?.find((account) => account.id === accountId)?.name ??
    accountId;
  const title = `Stuck Payments (${dashboard.stuckPaymentCount ?? "unknown"})`;
  if (dashboard.stuckPayments.length === 0) {
    return (
      <>
        <Typography variant="h5" component="h2" gutterBottom>
          {title}
        </Typography>
        <Typography>No Stuck Payments</Typography>
      </>
    );
  }
  return (
    <>
      <Typography variant="h5" component="h2" gutterBottom>
        {title}
      </Typography>
      <TableContainer component={Paper}>
        <Table aria-label="Stuck Payments">
          <TableHead>
            <TableRow>
              <TableCell>Made</TableCell>
              <TableCell>From</TableCell>
              <TableCell>Payee</TableCell>
              <TableCell align="right">Amount</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {dashboard.stuckPayments.map((payment) => (
              <TableRow key={payment.paymentId}>
                <TableCell>
                  <RouterLink
                    to="/payments/$paymentId"
                    params={{ paymentId: payment.paymentId }}
                  >
                    {dayjs(payment.madeAt).format("D MMM YYYY, HH:mm")}
                  </RouterLink>
                </TableCell>
                <TableCell>{nameOf(payment.debtorAccountId)}</TableCell>
                <TableCell>{payment.payeeName}</TableCell>
                <TableCell align="right">
                  {formatPence(payment.amountPence)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </>
  );
}

/** The Operator dashboard: live system health, polled every 2 s. */
export function DashboardScreen() {
  const dashboard = useQuery(dashboardQuery());
  return (
    <>
      <Typography variant="h4" component="h1" gutterBottom>
        Dashboard
      </Typography>
      {dashboard.isError && (
        <Alert severity="error">The dashboard is unavailable.</Alert>
      )}
      {dashboard.isPending && <Typography>loading…</Typography>}
      {dashboard.data && (
        <>
          <Typography variant="body2" color="text.secondary" gutterBottom>
            Payments per second over the last full minute; outcomes and
            Rejection Reasons over the last hour.
          </Typography>
          <Grid container spacing={2} sx={{ mb: 3 }}>
            <Figure label="Payments per second">
              <Value>
                {Number(dashboard.data.paymentsPerSecond.toFixed(2))}
              </Value>
            </Figure>
            <Figure label="Completed">
              <Value>{dashboard.data.outcomes.completed}</Value>
            </Figure>
            <Figure label="Rejected">
              <Value>{dashboard.data.outcomes.rejected}</Value>
            </Figure>
            <Figure label="Insufficient Funds">
              <Value>
                {dashboard.data.rejectionReasons.INSUFFICIENT_FUNDS}
              </Value>
            </Figure>
            <Figure label="Scheme Rejected">
              <Value>{dashboard.data.rejectionReasons.SCHEME_REJECTED}</Value>
            </Figure>
            <Figure label="Outbox lag">
              <Value>
                {dashboard.data.outboxLag.unpublished === null
                  ? "unknown"
                  : `${dashboard.data.outboxLag.unpublished} events`}
              </Value>
              <Typography variant="body2" color="text.secondary">
                oldest {seconds(dashboard.data.outboxLag.oldestAgeSeconds)}
              </Typography>
            </Figure>
            <Figure label="Projection Freshness">
              <Value>
                {seconds(dashboard.data.projectionFreshnessSeconds)}
              </Value>
            </Figure>
            <Figure label="Ledger">
              <BalancedStatus check={dashboard.data.balancedCheck} />
            </Figure>
          </Grid>
          <StuckPayments dashboard={dashboard.data} />
        </>
      )}
    </>
  );
}
