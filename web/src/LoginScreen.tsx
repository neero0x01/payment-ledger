import {
  Alert,
  Box,
  Button,
  Container,
  Paper,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { logIn } from "./ledgerApi";

export function LoginScreen() {
  const queryClient = useQueryClient();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const login = useMutation({
    mutationFn: () => logIn(username, password),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["session"] }),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    login.mutate();
  }

  return (
    <Container maxWidth="xs" sx={{ py: 8 }}>
      <Paper sx={{ p: 4 }}>
        <Box component="form" onSubmit={submit}>
          <Stack spacing={2}>
            <Typography variant="h5" component="h1">
              Payment Ledger
            </Typography>
            <Typography variant="h6" component="h2">
              Log in
            </Typography>
            <TextField
              label="Username"
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
            <TextField
              label="Password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            {login.isError && (
              <Alert severity="error">
                The login failed: {login.error.message}
              </Alert>
            )}
            <Button
              type="submit"
              variant="contained"
              disabled={login.isPending}
            >
              Log in
            </Button>
          </Stack>
        </Box>
      </Paper>
    </Container>
  );
}
