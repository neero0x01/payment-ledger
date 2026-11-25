import { screen, within } from "@testing-library/react";
import userEvent, { type UserEvent } from "@testing-library/user-event";
import {
  type FakePayment,
  type FakeTransaction,
  funded,
  installFakeLedger,
} from "./fakeLedger";
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
    },
    {
      accountId: ACCOUNT_B.id,
      side: "CREDIT",
      amountPence: 30_00,
    },
  ],
};

function installLedger({
  transactions = [PAID_B],
  payments = [PAYMENT],
  unavailableForFirstCorrection = false,
}: {
  transactions?: FakeTransaction[];
  payments?: FakePayment[];
  /** The first Correction request fails with 503 before reaching the ledger. */
  unavailableForFirstCorrection?: boolean;
} = {}) {
  let unavailable = unavailableForFirstCorrection;
  return installFakeLedger({
    accounts,
    // A has £100.00 before it pays B.
    entries: funded({ [ACCOUNT_A.id]: 100_00 }),
    transactions,
    payments,
    role: "OPERATOR",
    routes: (request) => {
      if (unavailable && request.path === "/v1/corrections") {
        unavailable = false;
        return new Response(null, { status: 503 });
      }
      return undefined;
    },
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
    },
    {
      accountId: ACCOUNT_B.id,
      side: "DEBIT",
      amountPence: 30_00,
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

  const reversedBy = await screen.findByText(/^Reversed by/);
  await user.click(
    await within(reversedBy).findByRole("link", {
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

test("a Transaction lists every Correction of it, and an Adjustment links back", async () => {
  const adjustment: FakeTransaction = {
    id: "019a0000-0000-7000-9000-000000000003",
    kind: "CORRECTION",
    bookingTime: "2026-11-04T09:00:00Z",
    adjustsTransactionId: PAID_B.id,
    correctionReason: "WRONG_AMOUNT",
    correctionNote: "Charged £5 too much",
    entries: [
      {
        accountId: CORRECTIONS.id,
        side: "DEBIT",
        amountPence: 5_00,
      },
      {
        accountId: ACCOUNT_A.id,
        side: "CREDIT",
        amountPence: 5_00,
      },
    ],
  };
  const reversing: FakeTransaction = {
    ...REVERSAL,
    id: "019a0000-0000-7000-9000-000000000004",
    kind: "CORRECTION",
    bookingTime: "2026-11-05T09:30:00Z",
    paymentId: undefined,
    correctionReason: "DUPLICATE_POSTING",
    correctionNote: "Paid twice by mistake",
  };
  installLedger({ transactions: [PAID_B, adjustment, reversing] });
  const user = userEvent.setup();
  renderApp(`/transactions/${PAID_B.id}`);
  await logIn(user);

  const list = await screen.findByRole("list", { name: "Corrections" });
  expect(
    await within(list).findByRole("link", {
      name: "Correction, 4 Nov 2026, 09:00",
    }),
  ).toBeInTheDocument();
  expect(
    await within(list).findByRole("link", {
      name: "Correction, 5 Nov 2026, 09:30",
    }),
  ).toBeInTheDocument();

  await user.click(
    within(list).getByRole("link", { name: "Correction, 4 Nov 2026, 09:00" }),
  );

  const corrects = await screen.findByText(/^Corrects/);
  expect(
    await within(corrects).findByRole("link", {
      name: "Payment, 3 Nov 2026, 10:15",
    }),
  ).toBeInTheDocument();
});

const correctionPosts = (ledger: ReturnType<typeof installLedger>) =>
  ledger.requests.filter(
    (r) => r.method === "POST" && r.url === "/v1/corrections",
  );

/** The Transaction detail's link to the Correction that reversed it, once it shows one. */
async function reversedByLink() {
  const reversedBy = await screen.findByText(/^Reversed by/);
  return within(reversedBy).findByRole("link", { name: /^Correction, / });
}

/** Fills in the Correction form for a Reversing Correction and submits it. */
async function postReversingCorrection(user: UserEvent) {
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
  return form;
}

test("an Operator posts a Reversing Correction of a Transaction", async () => {
  const ledger = installLedger();
  const user = userEvent.setup();
  renderApp(`/transactions/${PAID_B.id}`);
  await logIn(user);

  await postReversingCorrection(user);

  expect(await reversedByLink()).toBeInTheDocument();
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

test("a Reversing Correction moves the Balances the Accounts show", async () => {
  installLedger();
  const user = userEvent.setup();
  renderApp(`/transactions/${PAID_B.id}`);
  await logIn(user);
  await postReversingCorrection(user);
  await reversedByLink();

  await user.click(screen.getByRole("link", { name: "Accounts" }));

  expect(
    await screen.findByRole("row", { name: /^Account A £100\.00/ }),
  ).toBeInTheDocument();
});

test("reversing an already-reversed Transaction says it is already reversed", async () => {
  installLedger({ transactions: [PAID_B, REVERSAL] });
  const user = userEvent.setup();
  renderApp(`/transactions/${PAID_B.id}`);
  await logIn(user);

  const form = await postReversingCorrection(user);

  expect(await within(form).findByRole("alert")).toHaveTextContent(
    "This Transaction has already been reversed, so it can't be reversed again.",
  );
});

test("correcting a Transaction whose Payment is Pending says to wait for the scheme", async () => {
  installLedger({ payments: [{ ...PAYMENT, status: "PENDING" }] });
  const user = userEvent.setup();
  renderApp(`/transactions/${PAID_B.id}`);
  await logIn(user);

  const form = await postReversingCorrection(user);

  expect(await within(form).findByRole("alert")).toHaveTextContent(
    "This Transaction's Payment is still Pending. It can be corrected once the scheme has decided.",
  );
});

test("correcting a seeded Transaction says seeded data is read-only", async () => {
  installLedger({ transactions: [{ ...PAID_B, source: "SEED" }] });
  const user = userEvent.setup();
  renderApp(`/transactions/${PAID_B.id}`);
  await logIn(user);

  const form = await postReversingCorrection(user);

  expect(await within(form).findByRole("alert")).toHaveTextContent(
    "This Transaction is seeded data, which is read-only. No Correction can touch it.",
  );
});

test("retrying a failed Correction sends the same Idempotency-Key", async () => {
  const ledger = installLedger({ unavailableForFirstCorrection: true });
  const user = userEvent.setup();
  renderApp(`/transactions/${PAID_B.id}`);
  await logIn(user);

  const form = await postReversingCorrection(user);
  expect(await within(form).findByRole("alert")).toHaveTextContent(
    "The Correction failed",
  );
  await user.click(
    within(form).getByRole("button", { name: "Post Correction" }),
  );

  expect(await reversedByLink()).toBeInTheDocument();
  const keys = correctionPosts(ledger).map((r) =>
    r.headers.get("Idempotency-Key"),
  );
  expect(keys).toHaveLength(2);
  expect(keys[1]).toBe(keys[0]);
});

test("a double-clicked Correction is posted once", async () => {
  const ledger = installLedger();
  const user = userEvent.setup();
  renderApp(`/transactions/${PAID_B.id}`);
  await logIn(user);
  const form = await screen.findByRole("form", { name: "Correction" });
  await user.selectOptions(
    within(form).getByLabelText(/^Correction Reason/),
    "Duplicate Posting",
  );
  await user.type(within(form).getByLabelText(/^Note/), "Paid twice");

  await user.dblClick(
    within(form).getByRole("button", { name: "Post Correction" }),
  );

  expect(await reversedByLink()).toBeInTheDocument();
  const keys = new Set(
    correctionPosts(ledger).map((r) => r.headers.get("Idempotency-Key")),
  );
  expect(keys.size).toBe(1);
});

test("a new Correction after one is posted gets a new Idempotency-Key", async () => {
  const ledger = installLedger();
  const user = userEvent.setup();
  renderApp(`/transactions/${PAID_B.id}`);
  await logIn(user);

  await postReversingCorrection(user);
  await reversedByLink();
  const form = await postReversingCorrection(user);

  expect(await within(form).findByRole("alert")).toHaveTextContent(
    "already been reversed",
  );
  const keys = correctionPosts(ledger).map((r) =>
    r.headers.get("Idempotency-Key"),
  );
  expect(keys).toHaveLength(2);
  expect(keys[1]).not.toBe(keys[0]);
});

test("an Operator posts an Adjustment to a Customer Account of the Transaction", async () => {
  const ledger = installLedger();
  const user = userEvent.setup();
  renderApp(`/transactions/${PAID_B.id}`);
  await logIn(user);

  const form = await screen.findByRole("form", { name: "Correction" });
  await user.click(within(form).getByLabelText("Adjustment"));
  const account = within(form).getByLabelText(/^Customer Account/);
  await within(account).findByRole("option", { name: "Account B" });
  expect(
    within(account)
      .getAllByRole("option")
      .map((o) => o.textContent),
  ).toEqual(["Account A", "Account B"]);
  await user.selectOptions(account, "Account A");
  await user.type(within(form).getByLabelText(/^Amount/), "5.00");
  await user.selectOptions(
    within(form).getByLabelText(/^Direction/),
    "Credit the Customer Account",
  );
  await user.selectOptions(
    within(form).getByLabelText(/^Correction Reason/),
    "Wrong Amount",
  );
  await user.type(within(form).getByLabelText(/^Note/), "Charged £5 too much");
  await user.click(
    within(form).getByRole("button", { name: "Post Correction" }),
  );

  expect(
    await within(form).findByRole("link", { name: /^Correction, / }),
  ).toBeInTheDocument();
  expect(correctionPosts(ledger).map((r) => r.body)).toEqual([
    {
      kind: "ADJUSTMENT",
      transactionId: PAID_B.id,
      reason: "WRONG_AMOUNT",
      note: "Charged £5 too much",
      accountId: ACCOUNT_A.id,
      amountPence: 5_00,
      direction: "CREDIT",
    },
  ]);
});

test("an Operator opens a Transaction from an Account's Statement", async () => {
  installLedger();
  const user = userEvent.setup();
  renderApp(`/accounts/${ACCOUNT_A.id}`);
  await logIn(user);

  const statement = await screen.findByRole("grid", { name: "Statement" });
  await user.click(
    await within(statement).findByRole("link", { name: "3 Nov 2026, 10:15" }),
  );

  expect(
    await screen.findByRole("heading", { name: "Payment Transaction" }),
  ).toBeInTheDocument();
});
