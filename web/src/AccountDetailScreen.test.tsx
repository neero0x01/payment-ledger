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
