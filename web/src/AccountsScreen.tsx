import {
  Alert,
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
import { accountsQuery } from "./ledgerApi";
import { formatPence } from "./money";
import { RouterLink } from "./RouterLink";

/** The Accounts the logged-in Customer may see, with their Read Model Balances. */
export function AccountsScreen() {
  const accounts = useQuery(accountsQuery());
  return (
    <>
      <Typography variant="h4" component="h1" gutterBottom>
        Accounts
      </Typography>
      {accounts.isError && (
        <Alert severity="error">The Accounts are unavailable.</Alert>
      )}
      {accounts.isPending && <Typography>loading…</Typography>}
      {accounts.isSuccess && (
        <TableContainer component={Paper}>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Account</TableCell>
                <TableCell align="right">Balance</TableCell>
                <TableCell align="right">Available Balance</TableCell>
                <TableCell align="right">Overdraft Limit</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {accounts.data.map((account) => (
                <TableRow key={account.id}>
                  <TableCell component="th" scope="row">
                    <RouterLink
                      to="/accounts/$accountId"
                      params={{ accountId: account.id }}
                    >
                      {account.name}
                    </RouterLink>
                  </TableCell>
                  <TableCell align="right">
                    {formatPence(account.balancePence)}
                  </TableCell>
                  <TableCell align="right">
                    {formatPence(account.availableBalancePence)}
                  </TableCell>
                  <TableCell align="right">
                    {formatPence(account.overdraftLimitPence)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </>
  );
}
