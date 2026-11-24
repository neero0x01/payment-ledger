import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { funded, installFakeLedger } from "./fakeLedger";
import { renderApp } from "./renderApp";

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; max-age=0; path=/";
});

const sandbox = [
  { id: "019a0000-0000-7000-8000-0000000000c1", name: "Current Account" },
  { id: "019a0000-0000-7000-8000-0000000000c2", name: "Savings Account" },
];

const demoSessionRequests = (
  requests: ReturnType<typeof installFakeLedger>["requests"],
) =>
  requests.filter((r) => r.method === "POST" && r.url === "/v1/demo-sessions");

test("the landing page shows the claim, an architecture diagram and both ways to try the demo", async () => {
  installFakeLedger({ accounts: sandbox });

  renderApp("/");

  expect(
    await screen.findByRole("heading", {
      level: 1,
      name: /never double-charges/,
    }),
  ).toBeInTheDocument();
  expect(
    screen.getByRole("img", { name: /architecture/i }),
  ).toBeInTheDocument();
  expect(
    screen.getByRole("button", { name: "Try as customer" }),
  ).toBeInTheDocument();
  expect(
    screen.getByRole("button", { name: "Try as operator" }),
  ).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "API docs" })).toHaveAttribute(
    "href",
    "/docs",
  );
});

test("Try as customer opens the visitor's own funded Demo Sandbox", async () => {
  const ledger = installFakeLedger({
    accounts: sandbox,
    entries: funded({ [sandbox[0].id]: 1_000_00, [sandbox[1].id]: 250_00 }),
  });
  const user = userEvent.setup();
  renderApp("/");

  await user.click(
    await screen.findByRole("button", { name: "Try as customer" }),
  );

  expect(
    await screen.findByRole("row", { name: /^Current Account\b/ }),
  ).toHaveTextContent("£1,000.00");
  expect(
    screen.getByRole("row", { name: /^Savings Account\b/ }),
  ).toHaveTextContent("£250.00");
  const [demo] = demoSessionRequests(ledger.requests);
  expect(demo.body).toEqual({ role: "CUSTOMER" });
  expect(demo.headers.get("X-XSRF-TOKEN")).toBeTruthy();
});

test("Try as operator logs the visitor in as the demo Operator", async () => {
  const ledger = installFakeLedger({ accounts: sandbox });
  const user = userEvent.setup();
  renderApp("/");

  await user.click(
    await screen.findByRole("button", { name: "Try as operator" }),
  );

  expect(
    await screen.findByRole("row", { name: /^Current Account\b/ }),
  ).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Try as operator" })).toBeNull();
  expect(demoSessionRequests(ledger.requests)[0].body).toEqual({
    role: "OPERATOR",
  });
});

test("over the demo's limits, the landing page says why and stays put", async () => {
  installFakeLedger({ accounts: sandbox, demoRateLimited: true });
  const user = userEvent.setup();
  renderApp("/");

  await user.click(
    await screen.findByRole("button", { name: "Try as customer" }),
  );

  expect(await screen.findByRole("alert")).toHaveTextContent(
    "One Demo Sandbox per visitor every 10 minutes",
  );
  expect(
    screen.getByRole("button", { name: "Try as customer" }),
  ).toBeInTheDocument();
});
