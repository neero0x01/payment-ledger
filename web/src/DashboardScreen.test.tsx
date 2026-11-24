import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { installFakeLedger } from "./fakeLedger";
import { ACCOUNT_A } from "./localAccounts";
import { logIn, renderApp } from "./renderApp";

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; max-age=0; path=/";
});

const STUCK_PAYMENT_ID = "019a0000-0000-7000-8000-0000000000f9";

/** GET /v1/dashboard as the ledger answers it. */
function dashboard(overrides: Record<string, unknown> = {}) {
  return {
    paymentsPerSecond: 2.5,
    outcomes: { completed: 140, rejected: 7 },
    rejectionReasons: { INSUFFICIENT_FUNDS: 4, SCHEME_REJECTED: 3 },
    outboxLag: { unpublished: 12, oldestAgeSeconds: 0.4 },
    projectionFreshnessSeconds: 1.2,
    balancedCheck: {
      balanced: true,
      imbalancePence: 0,
      lastRunAt: "2026-11-24T10:15:30Z",
    },
    stuckPaymentCount: 1,
    stuckPayments: [
      {
        paymentId: STUCK_PAYMENT_ID,
        debtorAccountId: ACCOUNT_A.id,
        payeeName: "Jane Doe",
        payeeSortCode: "040004",
        payeeAccountNumber: "12345678",
        amountPence: 40_00,
        madeAt: "2026-11-24T10:05:00Z",
      },
    ],
    ...overrides,
  };
}

/** An Operator's ledger whose dashboard answers each GET with the next of the bodies, then the last. */
function installLedger(...bodies: unknown[]) {
  let requests = 0;
  installFakeLedger({
    accounts: [ACCOUNT_A],
    role: "OPERATOR",
    routes: (request) => {
      if (request.method !== "GET" || request.path !== "/v1/dashboard") {
        return undefined;
      }
      const body = bodies[Math.min(requests, bodies.length - 1)];
      requests += 1;
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    },
  });
  return { requests: () => requests };
}

/** The value shown under a figure's label. */
function figure(label: string) {
  return within(screen.getByRole("region", { name: label }));
}

test("the dashboard shows the system's health", async () => {
  installLedger(dashboard());
  const user = userEvent.setup();
  renderApp("/dashboard");
  await logIn(user);

  expect(
    await screen.findByRole("heading", { name: "Dashboard" }),
  ).toBeInTheDocument();
  expect(figure("Payments per second").getByText("2.5")).toBeInTheDocument();
  expect(figure("Completed").getByText("140")).toBeInTheDocument();
  expect(figure("Rejected").getByText("7")).toBeInTheDocument();
  expect(figure("Insufficient Funds").getByText("4")).toBeInTheDocument();
  expect(figure("Scheme Rejected").getByText("3")).toBeInTheDocument();
  expect(figure("Outbox lag").getByText("12 events")).toBeInTheDocument();
  expect(figure("Outbox lag").getByText("oldest 0.4 s")).toBeInTheDocument();
  expect(figure("Projection Freshness").getByText("1.2 s")).toBeInTheDocument();
  expect(figure("Ledger").getByText("Balanced")).toBeInTheDocument();
  expect(
    figure("Ledger").getByText(/checked 24 Nov 2026, 10:15:30/),
  ).toBeInTheDocument();
  const stuck = screen.getByRole("table", { name: "Stuck Payments" });
  expect(within(stuck).getByText("Account A")).toBeInTheDocument();
  expect(within(stuck).getByText("Jane Doe")).toBeInTheDocument();
  expect(within(stuck).getByText("£40.00")).toBeInTheDocument();
  expect(
    within(stuck).getByRole("link", { name: /24 Nov 2026, 10:05/ }),
  ).toHaveAttribute("href", `/payments/${STUCK_PAYMENT_ID}`);
});

test("the dashboard shows drift the balanced check found", async () => {
  installLedger(
    dashboard({
      balancedCheck: {
        balanced: false,
        imbalancePence: 12_34,
        lastRunAt: "2026-11-24T10:15:30Z",
      },
      stuckPaymentCount: 0,
      stuckPayments: [],
    }),
  );
  const user = userEvent.setup();
  renderApp("/dashboard");
  await logIn(user);

  expect(
    await screen.findByText("Not balanced: £12.34 drift"),
  ).toBeInTheDocument();
  expect(screen.getByText("No Stuck Payments")).toBeInTheDocument();
});

test("the dashboard polls every 2 s", async () => {
  const ledger = installLedger(
    dashboard(),
    dashboard({ paymentsPerSecond: 4 }),
  );
  const user = userEvent.setup();
  renderApp("/dashboard");
  await logIn(user);
  await screen.findByRole("heading", { name: "Dashboard" });
  const firstShown = Date.now();

  await waitFor(
    () => expect(figure("Payments per second").getByText("4")).toBeVisible(),
    { timeout: 3_000 },
  );
  expect(Date.now() - firstShown).toBeGreaterThanOrEqual(1_500);
  expect(ledger.requests()).toBe(2);
});

test("an Operator reaches the dashboard from the menu", async () => {
  installLedger(dashboard());
  const user = userEvent.setup();
  renderApp("/accounts");
  await logIn(user);

  await user.click(await screen.findByRole("link", { name: "Dashboard" }));

  expect(
    await screen.findByRole("heading", { name: "Dashboard" }),
  ).toBeInTheDocument();
});
