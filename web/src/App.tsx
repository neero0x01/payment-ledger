import {
  AppBar,
  Box,
  Button,
  Container,
  Toolbar,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { Link, Outlet } from "@tanstack/react-router";
import { fetchSession } from "./ledgerApi";
import { LoginScreen } from "./LoginScreen";

/** The screens once someone has logged in, and the login screen until then. */
export function App() {
  const session = useQuery({ queryKey: ["session"], queryFn: fetchSession });
  if (session.isPending) return <Typography sx={{ p: 2 }}>loading…</Typography>;
  if (session.isError) {
    return (
      <Typography role="alert" sx={{ p: 2 }}>
        The ledger is unavailable.
      </Typography>
    );
  }
  if (!session.data) return <LoginScreen />;
  return (
    <>
      <AppBar position="static">
        <Toolbar>
          <Typography variant="h6" component="div" sx={{ flexGrow: 1 }}>
            Payment Ledger
          </Typography>
          <Button color="inherit" component={Link} to="/accounts">
            Accounts
          </Button>
          <Button color="inherit" component={Link} to="/payments/new">
            New Payment
          </Button>
        </Toolbar>
      </AppBar>
      <Container component="main" sx={{ py: 3 }}>
        <Box>
          <Outlet />
        </Box>
      </Container>
    </>
  );
}
