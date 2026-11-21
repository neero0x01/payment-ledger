import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
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
