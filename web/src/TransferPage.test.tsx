import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { funded, installFakeLedger } from "./fakeLedger";
import { ACCOUNT_A, ACCOUNT_B } from "./localAccounts";
import { logIn, renderApp } from "./renderApp";

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; max-age=0; path=/";
});

test("an Internal Transfer updates both Balances", async () => {
  const ledger = installFakeLedger({
    accounts: [ACCOUNT_A, ACCOUNT_B],
    entries: funded({ [ACCOUNT_A.id]: 100_00 }),
  });
  const user = userEvent.setup();
  renderApp("/transfer");
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
