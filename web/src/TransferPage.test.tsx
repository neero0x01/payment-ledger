import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ACCOUNT_A, ACCOUNT_B } from "./localAccounts";
import { installFakeLedger } from "./fakeLedger";
import { TransferPage } from "./TransferPage";

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <TransferPage />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

test("lists the two Accounts with their Balances", async () => {
  installFakeLedger({ [ACCOUNT_A.id]: 100_00, [ACCOUNT_B.id]: 0 });

  renderPage();

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

  await user.selectOptions(screen.getByLabelText("From"), "Account A");
  await user.selectOptions(screen.getByLabelText("To"), "Account B");
  await user.type(screen.getByLabelText("Amount (£)"), "30.00");
  await user.click(screen.getByRole("button", { name: "Transfer" }));

  const accountA = screen.getByRole("row", { name: /^Account A\b/ });
  expect(await within(accountA).findByText("£70.00")).toBeInTheDocument();
  const accountB = screen.getByRole("row", { name: /^Account B\b/ });
  expect(await within(accountB).findByText("£30.00")).toBeInTheDocument();
  const payment = ledger.requests.find((r) => r.method === "POST");
  expect(payment?.headers.get("Idempotency-Key")).toBeTruthy();
});
