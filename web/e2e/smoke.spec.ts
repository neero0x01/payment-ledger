import { expect, test } from "@playwright/test";

// The happy path through the whole stack in Compose: the page, the ledger, both databases, Kafka
// and the projector (the From and To lists come from the Read Model).
test("a Customer logs in, makes an Internal Transfer and sees it Completed", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByLabel("Username").fill("local-customer");
  await page.getByLabel("Password").fill("local-customer");
  await page.getByRole("button", { name: "Log in" }).click();

  await page.getByRole("link", { name: "New Payment" }).click();
  const form = page.getByRole("form", { name: "New Payment" });
  await form.getByLabel("From").selectOption({ label: "Account A" });
  await form.getByLabel("To").selectOption({ label: "Account B" });
  await form.getByLabel("Amount (£)").fill("1.00");
  await form.getByRole("button", { name: "Pay" }).click();

  const status = page.getByRole("region", { name: "Payment Status" });
  await expect(status.getByText("Completed")).toBeVisible();
});
