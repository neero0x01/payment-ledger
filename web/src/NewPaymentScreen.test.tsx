import { screen, waitFor, within } from "@testing-library/react";
import userEvent, { type UserEvent } from "@testing-library/user-event";
import { funded, installFakeLedger } from "./fakeLedger";
import { ACCOUNT_A, ACCOUNT_B } from "./localAccounts";
import { logIn, renderApp } from "./renderApp";

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; max-age=0; path=/";
});

const accounts = [ACCOUNT_A, ACCOUNT_B];

async function openNewPayment(user: UserEvent) {
  renderApp("/payments/new");
  await logIn(user);
  await screen.findByRole("heading", { name: "New Payment" });
}

function paymentStatus() {
  return screen.findByRole("region", { name: "Payment Status" });
}

test("an Internal Transfer is Completed at once", async () => {
  const ledger = installFakeLedger({
    accounts,
    entries: funded({ [ACCOUNT_A.id]: 100_00 }),
  });
  const user = userEvent.setup();
  await openNewPayment(user);

  await user.selectOptions(await screen.findByLabelText("From"), "Account A");
  await user.selectOptions(screen.getByLabelText("To"), "Account B");
  await user.type(screen.getByLabelText("Amount (£)"), "30.00");
  await user.click(screen.getByRole("button", { name: "Pay" }));

  expect(
    await within(await paymentStatus()).findByText("Completed"),
  ).toBeInTheDocument();
  const posts = ledger.requests.filter(
    (r) => r.method === "POST" && r.url === "/v1/payments",
  );
  expect(posts).toHaveLength(1);
  expect(posts[0]?.body).toEqual({
    debtorAccountId: ACCOUNT_A.id,
    payee: { accountId: ACCOUNT_B.id },
    amountPence: 30_00,
  });
  expect(posts[0]?.headers.get("Idempotency-Key")).toBeTruthy();
  expect(posts[0]?.headers.get("X-XSRF-TOKEN")).toBe("csrf-after-login");
});

async function makeFasterPayment(user: UserEvent, amount = "25.00") {
  await user.selectOptions(await screen.findByLabelText("From"), "Account A");
  await user.click(screen.getByRole("radio", { name: "Faster Payment" }));
  await user.type(screen.getByLabelText("Sort code"), "123456");
  await user.type(screen.getByLabelText("Account number"), "12345678");
  await user.type(screen.getByLabelText("Name"), "Jo Bloggs");
  await user.type(screen.getByLabelText("Amount (£)"), amount);
  await user.click(screen.getByRole("button", { name: "Pay" }));
}

test("a Faster Payment shows Pending until the scheme Completes it", async () => {
  const ledger = installFakeLedger({
    accounts,
    entries: funded({ [ACCOUNT_A.id]: 100_00 }),
  });
  const user = userEvent.setup();
  await openNewPayment(user);

  await makeFasterPayment(user);

  const status = await paymentStatus();
  expect(await within(status).findByText("Pending")).toBeInTheDocument();
  expect(
    ledger.requests.find((r) => r.method === "POST" && r.url === "/v1/payments")
      ?.body,
  ).toEqual({
    debtorAccountId: ACCOUNT_A.id,
    payee: { sortCode: "123456", accountNumber: "12345678", name: "Jo Bloggs" },
    amountPence: 25_00,
  });

  ledger.decide({ status: "COMPLETED" });

  await waitFor(
    () => expect(within(status).getByText("Completed")).toBeInTheDocument(),
    { timeout: 5_000 },
  );
});

test("a Faster Payment the scheme rejects shows Rejected with its Rejection Reason", async () => {
  const ledger = installFakeLedger({
    accounts,
    entries: funded({ [ACCOUNT_A.id]: 100_00 }),
  });
  const user = userEvent.setup();
  await openNewPayment(user);
  await makeFasterPayment(user);
  const status = await paymentStatus();
  await within(status).findByText("Pending");

  ledger.decide({ status: "REJECTED", schemeReasonCode: "AC04" });

  await waitFor(
    () => expect(within(status).getByText("Rejected")).toBeInTheDocument(),
    { timeout: 5_000 },
  );
  expect(
    within(status).getByText("Scheme Rejected (AC04)"),
  ).toBeInTheDocument();
});

test("a Payment beyond the Available Balance shows Rejected for Insufficient Funds", async () => {
  installFakeLedger({ accounts, entries: funded({ [ACCOUNT_A.id]: 10_00 }) });
  const user = userEvent.setup();
  await openNewPayment(user);

  await user.selectOptions(await screen.findByLabelText("From"), "Account A");
  await user.selectOptions(screen.getByLabelText("To"), "Account B");
  await user.type(screen.getByLabelText("Amount (£)"), "10.01");
  await user.click(screen.getByRole("button", { name: "Pay" }));

  const status = await paymentStatus();
  expect(await within(status).findByText("Rejected")).toBeInTheDocument();
  expect(within(status).getByText("Insufficient Funds")).toBeInTheDocument();
});

async function fillInternalTransfer(user: UserEvent, amount: string) {
  await user.selectOptions(await screen.findByLabelText("From"), "Account A");
  await user.selectOptions(screen.getByLabelText("To"), "Account B");
  await user.clear(screen.getByLabelText("Amount (£)"));
  await user.type(screen.getByLabelText("Amount (£)"), amount);
}

function idempotencyKeysSent(ledger: ReturnType<typeof installFakeLedger>) {
  return ledger.requests
    .filter((r) => r.method === "POST" && r.url === "/v1/payments")
    .map((r) => r.headers.get("Idempotency-Key"));
}

test("a double-click on Pay never makes two Payments", async () => {
  const ledger = installFakeLedger({
    accounts,
    entries: funded({ [ACCOUNT_A.id]: 100_00 }),
  });
  const user = userEvent.setup();
  await openNewPayment(user);
  await fillInternalTransfer(user, "30.00");

  await user.dblClick(screen.getByRole("button", { name: "Pay" }));

  await within(await paymentStatus()).findByText("Completed");
  expect(ledger.payments).toHaveLength(1);
  expect(new Set(idempotencyKeysSent(ledger)).size).toBe(1);
});

test("paying again after a lost response resends the same Idempotency-Key", async () => {
  const ledger = installFakeLedger({
    accounts,
    entries: funded({ [ACCOUNT_A.id]: 100_00 }),
  });
  // The ledger makes the first Payment, but its response never arrives.
  const ledgerFetch = globalThis.fetch;
  let responsesLost = 0;
  vi.stubGlobal(
    "fetch",
    async (input: RequestInfo | URL, init?: RequestInit) => {
      const response = await ledgerFetch(input, init);
      if (init?.method === "POST" && input.toString() === "/v1/payments") {
        if (responsesLost === 0) {
          responsesLost += 1;
          throw new TypeError("Failed to fetch");
        }
      }
      return response;
    },
  );
  const user = userEvent.setup();
  await openNewPayment(user);
  await fillInternalTransfer(user, "30.00");
  await user.click(screen.getByRole("button", { name: "Pay" }));
  await screen.findByText(/The Payment failed/);

  await user.click(screen.getByRole("button", { name: "Pay" }));

  await within(await paymentStatus()).findByText("Completed");
  expect(ledger.payments).toHaveLength(1);
  const keys = idempotencyKeysSent(ledger);
  expect(keys).toHaveLength(2);
  expect(keys[1]).toBe(keys[0]);
});

test("a new submission after a Payment is made gets a new Idempotency-Key", async () => {
  const ledger = installFakeLedger({
    accounts,
    entries: funded({ [ACCOUNT_A.id]: 100_00 }),
  });
  const user = userEvent.setup();
  await openNewPayment(user);
  await fillInternalTransfer(user, "30.00");
  await user.click(screen.getByRole("button", { name: "Pay" }));
  await within(await paymentStatus()).findByText("Completed");

  await fillInternalTransfer(user, "20.00");
  await user.click(screen.getByRole("button", { name: "Pay" }));

  await waitFor(() => expect(ledger.payments).toHaveLength(2));
  const keys = idempotencyKeysSent(ledger);
  expect(keys[1]).not.toBe(keys[0]);
});
