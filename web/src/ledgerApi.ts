export type Balance = { accountId: string; balancePence: number };

export type InternalTransfer = {
  debtorAccountId: string;
  payeeAccountId: string;
  amountPence: number;
};

export type Payment = { id: string; status: string };

/** Makes an Internal Transfer. The Idempotency-Key belongs to one user submission. */
export async function makeInternalTransfer(
  transfer: InternalTransfer,
  idempotencyKey: string,
): Promise<Payment> {
  const response = await fetch("/v1/payments", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify({
      debtorAccountId: transfer.debtorAccountId,
      payee: { accountId: transfer.payeeAccountId },
      amountPence: transfer.amountPence,
    }),
  });
  if (!response.ok) {
    throw new Error(`Payment failed with ${response.status}`);
  }
  return response.json();
}

export async function fetchBalance(accountId: string): Promise<Balance> {
  const response = await fetch(`/v1/accounts/${accountId}/balance`);
  if (!response.ok) {
    throw new Error(`Balance of ${accountId} failed with ${response.status}`);
  }
  return response.json();
}
