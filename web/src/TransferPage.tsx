import { useQuery } from "@tanstack/react-query";
import { fetchBalance } from "./ledgerApi";
import { ACCOUNT_A, ACCOUNT_B } from "./localAccounts";
import { formatPence } from "./money";

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
