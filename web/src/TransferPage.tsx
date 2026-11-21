import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import {
  fetchBalance,
  type InternalTransfer,
  makeInternalTransfer,
} from "./ledgerApi";
import { ACCOUNT_A, ACCOUNT_B } from "./localAccounts";
import { formatPence, parsePounds } from "./money";

const accounts = [ACCOUNT_A, ACCOUNT_B];

export function TransferPage() {
  return (
    <main>
      <h1>Payment Ledger</h1>
      <table>
        <thead>
          <tr>
            <th>Account</th>
            <th>Balance</th>
          </tr>
        </thead>
        <tbody>
          {accounts.map((account) => (
            <AccountRow key={account.id} {...account} />
          ))}
        </tbody>
      </table>
      <TransferForm />
    </main>
  );
}

function AccountRow({ id, name }: { id: string; name: string }) {
  const balance = useQuery({
    queryKey: ["balance", id],
    queryFn: () => fetchBalance(id),
  });
  return (
    <tr>
      <th scope="row">{name}</th>
      <td>
        {balance.isSuccess
          ? formatPence(balance.data.balancePence)
          : balance.isError
            ? "unavailable"
            : "loading…"}
      </td>
    </tr>
  );
}

function TransferForm() {
  const queryClient = useQueryClient();
  const [from, setFrom] = useState(ACCOUNT_A.id);
  const [to, setTo] = useState(ACCOUNT_B.id);
  const [amount, setAmount] = useState("");
  const [invalid, setInvalid] = useState(false);
  const transfer = useMutation({
    mutationFn: ({
      transfer,
      idempotencyKey,
    }: {
      transfer: InternalTransfer;
      idempotencyKey: string;
    }) => makeInternalTransfer(transfer, idempotencyKey),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["balance"] }),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const amountPence = parsePounds(amount);
    setInvalid(amountPence === undefined);
    if (amountPence === undefined) return;
    transfer.mutate({
      transfer: { debtorAccountId: from, payeeAccountId: to, amountPence },
      idempotencyKey: crypto.randomUUID(),
    });
  }

  return (
    <form onSubmit={submit}>
      <h2>Internal Transfer</h2>
      <p>
        <label>
          From{" "}
          <select value={from} onChange={(e) => setFrom(e.target.value)}>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </label>
      </p>
      <p>
        <label>
          To{" "}
          <select value={to} onChange={(e) => setTo(e.target.value)}>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </label>
      </p>
      <p>
        <label>
          Amount (£){" "}
          <input
            inputMode="decimal"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
        </label>
      </p>
      {invalid && <p role="alert">Enter an amount in pounds, such as 30.00.</p>}
      {transfer.isError && (
        <p role="alert">The transfer failed: {transfer.error.message}</p>
      )}
      {transfer.isSuccess && (
        <p>
          Payment {transfer.data.id} is {transfer.data.status}.
        </p>
      )}
      <button type="submit" disabled={transfer.isPending}>
        Transfer
      </button>
    </form>
  );
}
