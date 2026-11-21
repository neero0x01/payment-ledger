export type Balance = { accountId: string; balancePence: number };

export async function fetchBalance(accountId: string): Promise<Balance> {
  const response = await fetch(`/v1/accounts/${accountId}/balance`);
  if (!response.ok) {
    throw new Error(`Balance of ${accountId} failed with ${response.status}`);
  }
  return response.json();
}
