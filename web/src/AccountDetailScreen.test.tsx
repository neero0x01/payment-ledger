import { screen, waitFor, within } from "@testing-library/react";
import userEvent, { type UserEvent } from "@testing-library/user-event";
import { type FakeEntry, installFakeLedger } from "./fakeLedger";
import { ACCOUNT_A, ACCOUNT_B } from "./localAccounts";
import { logIn, renderApp } from "./renderApp";

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; max-age=0; path=/";
});

const accounts = [ACCOUNT_A, ACCOUNT_B];

/** A was funded with £100.00, then paid B £30.00. */
const history: FakeEntry[] = [
  {
    accountId: ACCOUNT_A.id,
    bookingTime: "2026-11-01T09:00:00Z",
    side: "CREDIT",
    amountPence: 100_00,
  },
  {
    accountId: ACCOUNT_A.id,
    bookingTime: "2026-11-03T10:15:00Z",
    side: "DEBIT",
    amountPence: 30_00,
    counterpartyAccountId: ACCOUNT_B.id,
  },
  {
    accountId: ACCOUNT_B.id,
    bookingTime: "2026-11-03T10:15:00Z",
    side: "CREDIT",
    amountPence: 30_00,
    counterpartyAccountId: ACCOUNT_A.id,
  },
];

/** The value of an MUI X date field, which keeps it in a hidden input under the label. */
function dateField(label: string) {
  return screen
    .getAllByLabelText(label)
    .find((element) => element.tagName === "INPUT");
}

/** Types a date, digits only, into an MUI X date field's sections, starting at the day. */
async function typeDate(user: UserEvent, label: string, digits: string) {
  const field = await screen.findByRole("group", { name: label });
  await user.click(within(field).getByRole("spinbutton", { name: "Day" }));
  await user.keyboard(digits);
}

/** The Statement Lines on screen, as the text of each cell. */
async function statementLines() {
  const grid = await screen.findByRole("grid", { name: "Statement" });
  await within(grid).findAllByRole("gridcell");
  return within(grid)
    .getAllByRole("row")
    .map((row) =>
      within(row)
        .queryAllByRole("gridcell")
        .map((cell) => cell.textContent),
    )
    .filter((cells) => cells.length > 0);
}

test("an Account's detail shows its Statement, newest first", async () => {
  installFakeLedger({ accounts, entries: history });
  const user = userEvent.setup();
  renderApp("/accounts");
  await logIn(user);

  await user.click(await screen.findByRole("link", { name: "Account A" }));

  expect(
    await screen.findByRole("heading", { name: "Account A" }),
  ).toBeInTheDocument();
  expect(await statementLines()).toEqual([
    ["3 Nov 2026, 10:15", "Debit", "£30.00", "Account B", "£70.00"],
    ["1 Nov 2026, 09:00", "Credit", "£100.00", "", "£100.00"],
  ]);
});

test("the Statement filters by a range of Booking dates", async () => {
  installFakeLedger({ accounts, entries: history });
  const user = userEvent.setup();
  const { router } = renderApp(`/accounts/${ACCOUNT_A.id}`);
  await logIn(user);
  await statementLines();

  await typeDate(user, "From", "02112026");
  await typeDate(user, "To", "03112026");

  await waitFor(async () =>
    expect(await statementLines()).toEqual([
      ["3 Nov 2026, 10:15", "Debit", "£30.00", "Account B", "£70.00"],
    ]),
  );
  expect(router.state.location.search).toEqual({
    from: "2026-11-02",
    to: "2026-11-03",
  });
});

test("a Statement link with a date range opens filtered", async () => {
  installFakeLedger({ accounts, entries: history });
  const user = userEvent.setup();
  renderApp(`/accounts/${ACCOUNT_A.id}?to=2026-11-02`);
  await logIn(user);

  expect(await statementLines()).toEqual([
    ["1 Nov 2026, 09:00", "Credit", "£100.00", "", "£100.00"],
  ]);
  expect(dateField("To")).toHaveValue("02/11/2026");
});

test("the Statement pages through Statement Lines 50 at a time", async () => {
  const payments: FakeEntry[] = Array.from({ length: 54 }, (_, i) => ({
    accountId: ACCOUNT_A.id,
    bookingTime: new Date(Date.UTC(2026, 9, 2, i)).toISOString(),
    side: "DEBIT",
    amountPence: 1_00,
    counterpartyAccountId: ACCOUNT_B.id,
  }));
  installFakeLedger({
    accounts,
    entries: [
      {
        accountId: ACCOUNT_A.id,
        bookingTime: "2026-10-01T09:00:00Z",
        side: "CREDIT",
        amountPence: 1_000_00,
      },
      ...payments,
    ],
  });
  const user = userEvent.setup();
  renderApp(`/accounts/${ACCOUNT_A.id}`);
  await logIn(user);

  const firstPage = await statementLines();
  expect(firstPage).toHaveLength(50);
  expect(firstPage[0]).toEqual([
    "4 Oct 2026, 05:00",
    "Debit",
    "£1.00",
    "Account B",
    "£946.00",
  ]);

  await user.click(screen.getByRole("button", { name: "Go to next page" }));

  await waitFor(async () => expect(await statementLines()).toHaveLength(5));
  expect((await statementLines()).at(-1)).toEqual([
    "1 Oct 2026, 09:00",
    "Credit",
    "£1,000.00",
    "",
    "£1,000.00",
  ]);
  expect(
    screen.getByRole("button", { name: "Go to next page" }),
  ).toBeDisabled();

  await user.click(screen.getByRole("button", { name: "Go to previous page" }));

  await waitFor(async () => expect(await statementLines()).toHaveLength(50));
});

test("the As-At Balance shows the Balance at a chosen moment", async () => {
  installFakeLedger({ accounts, entries: history });
  const user = userEvent.setup();
  const { router } = renderApp(`/accounts/${ACCOUNT_A.id}`);
  await logIn(user);
  const asAtBalance = await screen.findByRole("region", {
    name: "As-At Balance",
  });

  await typeDate(user, "As at", "031120261015");

  expect(await within(asAtBalance).findByText("£70.00")).toBeInTheDocument();
  expect(router.state.location.search).toEqual({
    asAt: "2026-11-03T10:15:00.000Z",
  });
});

test("an As-At Balance link opens at that moment", async () => {
  installFakeLedger({ accounts, entries: history });
  const user = userEvent.setup();
  renderApp(`/accounts/${ACCOUNT_A.id}?asAt=2026-11-03T10:14:00.000Z`);
  await logIn(user);

  const asAtBalance = await screen.findByRole("region", {
    name: "As-At Balance",
  });
  expect(await within(asAtBalance).findByText("£100.00")).toBeInTheDocument();
  expect(dateField("As at")).toHaveValue("03/11/2026 10:14");
});

test("the Statement says it is updating while it trails the latest Payment", async () => {
  const ledger = installFakeLedger({
    accounts,
    entries: history,
    trailing: true,
  });
  const user = userEvent.setup();
  renderApp("/payments/new");
  await logIn(user);
  await user.selectOptions(await screen.findByLabelText("From"), "Account A");
  await user.selectOptions(screen.getByLabelText("To"), "Account B");
  await user.type(screen.getByLabelText("Amount (£)"), "20.00");
  await user.click(screen.getByRole("button", { name: "Pay" }));
  await screen.findByText("Completed");

  await user.click(screen.getByRole("link", { name: "Accounts" }));
  await user.click(await screen.findByRole("link", { name: "Account A" }));

  expect(await screen.findByText("updating…")).toBeInTheDocument();
  expect(await statementLines()).toHaveLength(2);

  ledger.project();

  await waitFor(() => expect(screen.queryByText("updating…")).toBeNull(), {
    timeout: 5_000,
  });
  expect((await statementLines())[0]).toEqual([
    expect.any(String),
    "Debit",
    "£20.00",
    "Account B",
    "£50.00",
  ]);
});

test("a Statement that has caught up does not say it is updating", async () => {
  installFakeLedger({ accounts, entries: history, trailing: true });
  const user = userEvent.setup();
  renderApp(`/accounts/${ACCOUNT_A.id}`);
  await logIn(user);

  expect(await statementLines()).toHaveLength(2);
  expect(screen.queryByText("updating…")).toBeNull();
});
