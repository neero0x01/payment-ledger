import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { fetchSession, logIn } from "./ledgerApi";
import { TransferPage } from "./TransferPage";

/** Shows the transfer page once a Customer has logged in, and the login form until then. */
export function App() {
  const session = useQuery({ queryKey: ["session"], queryFn: fetchSession });
  if (session.isPending) return <p>loading…</p>;
  if (session.isError) return <p role="alert">The ledger is unavailable.</p>;
  return session.data ? <TransferPage /> : <LoginForm />;
}

function LoginForm() {
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
    <main>
      <h1>Payment Ledger</h1>
      <form onSubmit={submit}>
        <h2>Log in</h2>
        <p>
          <label>
            Username{" "}
            <input
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </label>
        </p>
        <p>
          <label>
            Password{" "}
            <input
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>
        </p>
        {login.isError && (
          <p role="alert">The login failed: {login.error.message}</p>
        )}
        <button type="submit" disabled={login.isPending}>
          Log in
        </button>
      </form>
    </main>
  );
}
