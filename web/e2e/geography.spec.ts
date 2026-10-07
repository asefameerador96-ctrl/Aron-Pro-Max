import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

test("geography: list levels, create a division under a wing with a reason, edit a zone, deactivate", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.getByRole("link", { name: "মাস্টার ডেটা" }).click();
  await expect(page.getByTestId("group-geography")).toBeVisible();
  await page.getByTestId("group-geography").getByRole("link", { name: "বিভাগ", exact: true }).click();
  await expect(page.getByTestId("data-table")).toContainText("Dhaka North");
  // parent shows its label, not a bare id
  await expect(page.getByTestId("data-table")).toContainText("W-1 · Dhaka Wing");

  await page.getByTestId("create-link").click();
  await page.locator("#f-code").fill("D-9");
  await page.locator("#f-name").fill("Sylhet");
  await page.locator("#f-parent_id").selectOption({ label: "W-2 · Chattogram Wing" });
  await page.getByRole("button", { name: "তৈরি করুন" }).click();
  await expect(page.getByTestId("error-reason")).toBeVisible();
  await page.locator("#reason").fill("New division for the Sylhet expansion");
  await page.getByRole("button", { name: "তৈরি করুন" }).click();
  await expect(page.getByTestId("data-table")).toContainText("Sylhet");

  // duplicate code is refused with a Bangla message
  await page.getByTestId("create-link").click();
  await page.locator("#f-code").fill("D-9");
  await page.locator("#f-name").fill("Again");
  await page.locator("#f-parent_id").selectOption({ label: "W-1 · Dhaka Wing" });
  await page.locator("#reason").fill("Trying the same code again");
  await page.getByRole("button", { name: "তৈরি করুন" }).click();
  await expect(page.getByTestId("form-error")).toHaveText("এই কোডের রেকর্ড আগে থেকেই আছে।");

  // zone: edit phone, then deactivate
  await page.goto("/admin/zones");
  await page.getByRole("link", { name: "সম্পাদনা" }).first().click();
  await expect(page.locator("#f-code")).toHaveCount(0); // code is immutable
  await page.locator("#f-pda_contact_no").fill("12");
  await page.locator("#reason").fill("Fixing the PDA contact number");
  await page.getByRole("button", { name: "সংরক্ষণ" }).click();
  await expect(page.getByTestId("error-f-pda_contact_no")).toBeVisible();
  await page.locator("#f-pda_contact_no").fill("+8801700000001");
  await page.locator("#f-status").selectOption("inactive");
  await page.getByRole("button", { name: "সংরক্ষণ" }).click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  const state = await (await fetch(`${MOCK}/__mock/state`)).json();
  const last = state.audit.at(-1);
  expect(last).toMatchObject({ entity: "geo_node", action: "geo_node.update", reason: "Fixing the PDA contact number" });
  expect(last.after).toMatchObject({ status: "inactive", pda_contact_no: "+8801700000001" });
});

test("geography is read-only for support", async ({ page }) => {
  await loginOk(page, "support1", "support-pass-1", "123456");
  const res = await page.goto("/admin/zones/new");
  expect(res?.status()).toBe(403);
});
