import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { funded, installFakeLedger } from "./fakeLedger";
import { ACCOUNT_A, ACCOUNT_B } from "./localAccounts";
import { logIn, renderApp } from "./renderApp";

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; max-age=0; path=/";
});

const accounts = [
  { ...ACCOUNT_A, overdraftLimitPence: 50_00 },
  { ...ACCOUNT_B, overdraftLimitPence: 0 },
];

test("asks the Customer to log in before showing the Accounts", async () => {
  installFakeLedger({ accounts, entries: funded({ [ACCOUNT_A.id]: 100_00 }) });

  renderApp("/accounts");

  expect(await screen.findByLabelText("Username")).toBeInTheDocument();
  expect(screen.queryByRole("row", { name: /^Account A\b/ })).toBeNull();
});

test("a wrong password says the login failed", async () => {
  installFakeLedger({ accounts, entries: funded({ [ACCOUNT_A.id]: 100_00 }) });
  const user = userEvent.setup();
  renderApp("/accounts");

  await logIn(user, "not the password");

  expect(await screen.findByRole("alert")).toHaveTextContent(
    "The login failed",
  );
  expect(screen.queryByRole("row", { name: /^Account A\b/ })).toBeNull();
});

test("shows each Account's Balance, Available Balance and Overdraft Limit", async () => {
  installFakeLedger({
    accounts,
    entries: funded({ [ACCOUNT_A.id]: 100_00, [ACCOUNT_B.id]: 20_00 }),
  });
  const user = userEvent.setup();
  renderApp("/");

  await logIn(user);

  const accountA = await screen.findByRole("row", { name: /^Account A\b/ });
  const cells = (row: HTMLElement) =>
    within(row)
      .getAllByRole("cell")
      .map((cell) => cell.textContent);
  expect(
    screen.getAllByRole("columnheader").map((h) => h.textContent),
  ).toEqual(["Account", "Balance", "Available Balance", "Overdraft Limit"]);
  expect(cells(accountA)).toEqual(["£100.00", "£150.00", "£50.00"]);
  const accountB = screen.getByRole("row", { name: /^Account B\b/ });
  expect(cells(accountB)).toEqual(["£20.00", "£20.00", "£0.00"]);
});
