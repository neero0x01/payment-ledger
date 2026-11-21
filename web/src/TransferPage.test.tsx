import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent, { type UserEvent } from "@testing-library/user-event";
import { App } from "./App";
import { installFakeLedger } from "./fakeLedger";
import { ACCOUNT_A, ACCOUNT_B } from "./localAccounts";

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>,
  );
}

async function logIn(user: UserEvent, password = "local-customer") {
  await user.type(await screen.findByLabelText("Username"), "local-customer");
  await user.type(screen.getByLabelText("Password"), password);
  await user.click(screen.getByRole("button", { name: "Log in" }));
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; max-age=0; path=/";
});

test("asks the Customer to log in before showing the Accounts", async () => {
  installFakeLedger({ [ACCOUNT_A.id]: 100_00, [ACCOUNT_B.id]: 0 });

  renderPage();

  expect(await screen.findByLabelText("Username")).toBeInTheDocument();
  expect(screen.queryByRole("row", { name: /^Account A\b/ })).toBeNull();
});

test("a wrong password says the login failed", async () => {
  installFakeLedger({ [ACCOUNT_A.id]: 100_00, [ACCOUNT_B.id]: 0 });
  const user = userEvent.setup();
  renderPage();

  await logIn(user, "not the password");

  expect(await screen.findByRole("alert")).toHaveTextContent(
    "The login failed",
  );
  expect(screen.queryByRole("row", { name: /^Account A\b/ })).toBeNull();
});

test("lists the two Accounts with their Balances", async () => {
  installFakeLedger({ [ACCOUNT_A.id]: 100_00, [ACCOUNT_B.id]: 0 });
  const user = userEvent.setup();
  renderPage();

  await logIn(user);

  const accountA = await screen.findByRole("row", { name: /^Account A\b/ });
  expect(await within(accountA).findByText("£100.00")).toBeInTheDocument();
  const accountB = screen.getByRole("row", { name: /^Account B\b/ });
  expect(await within(accountB).findByText("£0.00")).toBeInTheDocument();
});

test("an Internal Transfer updates both Balances", async () => {
  const ledger = installFakeLedger({
    [ACCOUNT_A.id]: 100_00,
    [ACCOUNT_B.id]: 0,
  });
  const user = userEvent.setup();
  renderPage();
  await logIn(user);

  await user.selectOptions(await screen.findByLabelText("From"), "Account A");
  await user.selectOptions(screen.getByLabelText("To"), "Account B");
  await user.type(screen.getByLabelText("Amount (£)"), "30.00");
  await user.click(screen.getByRole("button", { name: "Transfer" }));

  const accountA = screen.getByRole("row", { name: /^Account A\b/ });
  expect(await within(accountA).findByText("£70.00")).toBeInTheDocument();
  const accountB = screen.getByRole("row", { name: /^Account B\b/ });
  expect(await within(accountB).findByText("£30.00")).toBeInTheDocument();
  const payment = ledger.requests.find(
    (r) => r.method === "POST" && r.url === "/v1/payments",
  );
  expect(payment?.headers.get("Idempotency-Key")).toBeTruthy();
  expect(payment?.headers.get("X-XSRF-TOKEN")).toBe("csrf-after-login");
});
