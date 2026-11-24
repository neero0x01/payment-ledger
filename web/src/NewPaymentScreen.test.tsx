import { screen, within } from "@testing-library/react";
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
