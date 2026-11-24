import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { installFakeLedger } from "./fakeLedger";
import {
  type FakePayment,
  type FakeTransaction,
  fakeTransactions,
} from "./fakeTransactions";
import { ACCOUNT_A, ACCOUNT_B } from "./localAccounts";
import { logIn, renderApp } from "./renderApp";

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; max-age=0; path=/";
});

const CORRECTIONS = {
  id: "019a0000-0000-7000-8000-0000000000c0",
  name: "Corrections",
  kind: "INTERNAL" as const,
};
const accounts = [ACCOUNT_A, ACCOUNT_B, CORRECTIONS];

const PAYMENT: FakePayment = {
  id: "019a0000-0000-7000-8000-0000000000f1",
  type: "INTERNAL_TRANSFER",
  status: "COMPLETED",
  debtorAccountId: ACCOUNT_A.id,
  payee: { accountId: ACCOUNT_B.id },
  amountPence: 30_00,
};

/** A paid B £30.00, leaving A with £70.00. */
const PAID_B: FakeTransaction = {
  id: "019a0000-0000-7000-9000-000000000001",
  kind: "PAYMENT",
  bookingTime: "2026-11-03T10:15:00Z",
  paymentId: PAYMENT.id,
  entries: [
    {
      accountId: ACCOUNT_A.id,
      side: "DEBIT",
      amountPence: 30_00,
      balanceAfterPence: 70_00,
    },
    {
      accountId: ACCOUNT_B.id,
      side: "CREDIT",
      amountPence: 30_00,
      balanceAfterPence: 30_00,
    },
  ],
};

function installLedger({
  transactions = [PAID_B],
  payments = [PAYMENT],
}: { transactions?: FakeTransaction[]; payments?: FakePayment[] } = {}) {
  const writeSide = fakeTransactions({
    transactions,
    payments,
    correctionsAccountId: CORRECTIONS.id,
  });
  return installFakeLedger({
    accounts,
    role: "OPERATOR",
    routes: writeSide.routes,
  });
}

/** The rows of a table on screen, as the text of each cell. */
function rowsOf(table: HTMLElement) {
  return within(table)
    .getAllByRole("row")
    .map((row) =>
      within(row)
        .queryAllByRole("cell")
        .map((cell) => cell.textContent),
    )
    .filter((cells) => cells.length > 0);
}

test("a Transaction's detail shows its Entries and links to its Payment", async () => {
  installLedger();
  const user = userEvent.setup();
  renderApp(`/transactions/${PAID_B.id}`);
  await logIn(user);

  expect(
    await screen.findByRole("heading", { name: "Payment Transaction" }),
  ).toBeInTheDocument();
  const entries = await screen.findByRole("table", { name: "Entries" });
  expect(await within(entries).findByText("Account A")).toBeInTheDocument();
  expect(rowsOf(entries)).toEqual([
    ["Account A", "Debit", "£30.00", "£70.00"],
    ["Account B", "Credit", "£30.00", "£30.00"],
  ]);

  await user.click(screen.getByRole("link", { name: "Payment" }));

  expect(
    await screen.findByRole("heading", { name: "Internal Transfer" }),
  ).toBeInTheDocument();
  expect(await screen.findByText("Completed")).toBeInTheDocument();
  expect(
    screen.getByRole("link", { name: /3 Nov 2026, 10:15/ }),
  ).toBeInTheDocument();
});

/** The Reversal of PAID_B after a Scheme Rejection, returning A's £30.00. */
const REVERSAL: FakeTransaction = {
  id: "019a0000-0000-7000-9000-000000000002",
  kind: "REVERSAL",
  bookingTime: "2026-11-03T10:16:00Z",
  paymentId: PAYMENT.id,
  reversesTransactionId: PAID_B.id,
  entries: [
    {
      accountId: ACCOUNT_A.id,
      side: "CREDIT",
      amountPence: 30_00,
      balanceAfterPence: 100_00,
    },
    {
      accountId: ACCOUNT_B.id,
      side: "DEBIT",
      amountPence: 30_00,
      balanceAfterPence: 0,
    },
  ],
};

test("a reversed Transaction links to its Reversal, which links back", async () => {
  installLedger({ transactions: [PAID_B, REVERSAL] });
  const user = userEvent.setup();
  renderApp(`/transactions/${PAID_B.id}`);
  await logIn(user);

  await user.click(
    await screen.findByRole("link", { name: "Reversal, 3 Nov 2026, 10:16" }),
  );

  expect(
    await screen.findByRole("heading", { name: "Reversal Transaction" }),
  ).toBeInTheDocument();
  expect(
    await screen.findByRole("link", { name: "Payment, 3 Nov 2026, 10:15" }),
  ).toBeInTheDocument();
});

test("a Correction's detail shows its Correction Reason and note", async () => {
  const correction: FakeTransaction = {
    ...REVERSAL,
    kind: "CORRECTION",
    paymentId: undefined,
    correctionReason: "DUPLICATE_POSTING",
    correctionNote: "Paid twice by mistake",
  };
  installLedger({ transactions: [PAID_B, correction] });
  const user = userEvent.setup();
  renderApp(`/transactions/${PAID_B.id}`);
  await logIn(user);

  await user.click(
    await screen.findByRole("link", {
      name: "Correction, 3 Nov 2026, 10:16",
    }),
  );

  expect(
    await screen.findByRole("heading", { name: "Correction Transaction" }),
  ).toBeInTheDocument();
  expect(
    screen.getByText("Correction Reason: Duplicate Posting"),
  ).toBeInTheDocument();
  expect(screen.getByText("Note: Paid twice by mistake")).toBeInTheDocument();
});

const correctionPosts = (ledger: ReturnType<typeof installLedger>) =>
  ledger.requests.filter(
    (r) => r.method === "POST" && r.url === "/v1/corrections",
  );

test("an Operator posts a Reversing Correction of a Transaction", async () => {
  const ledger = installLedger();
  const user = userEvent.setup();
  renderApp(`/transactions/${PAID_B.id}`);
  await logIn(user);

  const form = await screen.findByRole("form", { name: "Correction" });
  await user.click(within(form).getByLabelText("Reversing Correction"));
  await user.selectOptions(
    within(form).getByLabelText(/^Correction Reason/),
    "Duplicate Posting",
  );
  await user.type(within(form).getByLabelText(/^Note/), "Paid twice");
  await user.click(
    within(form).getByRole("button", { name: "Post Correction" }),
  );

  expect(
    await screen.findByRole("link", { name: /^Correction, / }),
  ).toBeInTheDocument();
  const [post] = correctionPosts(ledger);
  expect(post?.body).toEqual({
    kind: "REVERSING",
    transactionId: PAID_B.id,
    reason: "DUPLICATE_POSTING",
    note: "Paid twice",
  });
  expect(post?.headers.get("Idempotency-Key")).toBeTruthy();
  expect(post?.headers.get("X-XSRF-TOKEN")).toBe("csrf-after-login");
});
