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

test("a Faster Payment with an invalid Payee shows each problem beside its field", async () => {
  const ledger = installFakeLedger({
    accounts,
    entries: funded({ [ACCOUNT_A.id]: 100_00 }),
  });
  const user = userEvent.setup();
  await openNewPayment(user);
  await user.selectOptions(await screen.findByLabelText("From"), "Account A");
  await user.click(screen.getByRole("radio", { name: "Faster Payment" }));
  await user.type(screen.getByLabelText("Sort code"), "12345");
  await user.type(screen.getByLabelText("Account number"), "1234567x");
  await user.type(screen.getByLabelText("Amount (£)"), "0");

  await user.click(screen.getByRole("button", { name: "Pay" }));

  expect(screen.getByLabelText("Sort code")).toHaveAccessibleDescription(
    "Enter the six-digit sort code",
  );
  expect(screen.getByLabelText("Account number")).toHaveAccessibleDescription(
    "Enter the eight-digit account number",
  );
  expect(screen.getByLabelText("Name")).toHaveAccessibleDescription(
    "Enter the Payee's name",
  );
  expect(screen.getByLabelText("Amount (£)")).toHaveAccessibleDescription(
    "Enter an amount more than £0.00",
  );
  expect(idempotencyKeysSent(ledger)).toHaveLength(0);
});

test("an Internal Transfer to the payer's own Account or over £1,000,000.00 is shown as invalid", async () => {
  const ledger = installFakeLedger({
    accounts,
    entries: funded({ [ACCOUNT_A.id]: 100_00 }),
  });
  const user = userEvent.setup();
  await openNewPayment(user);
  await user.selectOptions(await screen.findByLabelText("From"), "Account A");
  await user.selectOptions(screen.getByLabelText("To"), "Account A");
  await user.type(screen.getByLabelText("Amount (£)"), "1000000.01");

  await user.click(screen.getByRole("button", { name: "Pay" }));

  expect(screen.getByLabelText("To")).toHaveAccessibleDescription(
    "Choose an Account other than the one paying",
  );
  expect(screen.getByLabelText("Amount (£)")).toHaveAccessibleDescription(
    "Enter an amount of at most £1,000,000.00",
  );
  expect(idempotencyKeysSent(ledger)).toHaveLength(0);
});

test("a validation problem from the ledger that names a field is shown beside it", async () => {
  installFakeLedger({ accounts, entries: funded({ [ACCOUNT_A.id]: 100_00 }) });
  const ledgerFetch = globalThis.fetch;
  vi.stubGlobal(
    "fetch",
    async (input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "POST" && input.toString() === "/v1/payments") {
        return new Response(
          JSON.stringify({
            type: "/problems/validation-failed",
            title: "Validation failed",
            status: 400,
            detail: "The Payee's sort code is not one the scheme knows",
            field: "payee.sortCode",
          }),
          {
            status: 400,
            headers: { "Content-Type": "application/problem+json" },
          },
        );
      }
      return ledgerFetch(input, init);
    },
  );
  const user = userEvent.setup();
  await openNewPayment(user);
  await makeFasterPayment(user);

  const sortCode = screen.getByLabelText("Sort code");
  await waitFor(() =>
    expect(sortCode).toHaveAccessibleDescription(
      "The Payee's sort code is not one the scheme knows",
    ),
  );
  expect(sortCode).toHaveAttribute("aria-invalid", "true");
  const form = screen.getByRole("form", { name: "New Payment" });
  expect(within(form).queryByRole("alert")).toBeNull();

  await user.type(sortCode, "1");

  expect(sortCode).not.toHaveAccessibleDescription(
    "The Payee's sort code is not one the scheme knows",
  );
});

test("a validation problem from the ledger is shown in the form", async () => {
  installFakeLedger({ accounts, entries: funded({ [ACCOUNT_A.id]: 100_00 }) });
  const ledgerFetch = globalThis.fetch;
  vi.stubGlobal(
    "fetch",
    async (input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "POST" && input.toString() === "/v1/payments") {
        return new Response(
          JSON.stringify({
            type: "/problems/validation-failed",
            title: "Validation failed",
            status: 400,
            detail: "The Payee's sort code must be six digits",
          }),
          {
            status: 400,
            headers: { "Content-Type": "application/problem+json" },
          },
        );
      }
      return ledgerFetch(input, init);
    },
  );
  const user = userEvent.setup();
  await openNewPayment(user);
  await makeFasterPayment(user);

  const form = screen.getByRole("form", { name: "New Payment" });
  expect(await within(form).findByRole("alert")).toHaveTextContent(
    "The Payee's sort code must be six digits",
  );
  expect(screen.queryByRole("region", { name: "Payment Status" })).toBeNull();
});
