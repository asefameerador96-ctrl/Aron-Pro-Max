import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});
const stateOf = async () => (await fetch(`${MOCK}/__mock/state`)).json();

test("retailer detail: four sections, an audited edit, reopen a wrongly closed outlet", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.goto("/admin/outlets");
  await expect(page.getByTestId("data-table")).toContainText("Banani Store");
  await page.getByRole("row", { name: /Banani Store/ }).getByRole("link", { name: "সম্পাদনা" }).click();
  for (const heading of ["মৌলিক তথ্য", "ঠিকানা ও অবস্থান", "ব্যবসা", "অতিরিক্ত তথ্য"]) await expect(page.getByRole("heading", { name: heading })).toBeVisible();
  await page.locator("#f-owner_name").fill("Md. Rahim Uddin");
  await page.locator("#f-lat").fill("৯১"); // out of range, Bengali digits are read
  await page.locator("#reason").fill("Owner name corrected from the licence");
  await page.getByRole("button", { name: "সংরক্ষণ" }).click();
  await expect(page.getByTestId("error-f-lat")).toBeVisible();
  await page.locator("#f-lat").fill("23.8");
  await page.getByRole("button", { name: "সংরক্ষণ" }).click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  expect((await stateOf()).audit.at(-1)).toMatchObject({ entity: "outlet", reason: "Owner name corrected from the licence", after: { owner_name: "Md. Rahim Uddin", lat: 23.8 } });

  await page.goto("/admin/outlets");
  await expect(page.getByRole("row", { name: /Banani Store/ }).getByTestId("action-reopen")).toHaveCount(0); // active
  await page.getByRole("row", { name: /Wrongly Closed Tea Stall/ }).getByTestId("action-reopen").click();
  await page.locator("#reason").fill("Closed by mistake, the stall is open");
  await page.getByRole("button", { name: "আউটলেট আবার চালু করুন" }).click();
  await page.waitForURL(/\/admin\/outlets$/);
  expect((await stateOf()).tables.outlets.find((o: { id: number }) => o.id === 4)).toMatchObject({ status: "active" });
});

test("outlet approval panel: a DMO sees only the panel, verifies then approves a new outlet and a closure", async ({ page }) => {
  await loginOk(page, "dmo1", "dmo-pass-1");
  await expect(page.getByRole("link", { name: "আউটলেট অনুমোদন" })).toBeVisible();
  await expect(page.getByRole("link", { name: "মাস্টার ডেটা" })).toHaveCount(0);
  expect((await page.goto("/admin/clusters"))?.status()).toBe(403);
  await page.goto("/admin/outlet-requests");
  await expect(page.getByTestId("data-table")).toContainText("New Corner Mart");

  // filter New / Close / Info by type
  await page.locator('select[name="request_type"]').selectOption("close");
  await page.getByTestId("filter-bar").locator('button[type="submit"]').click();
  await expect(page.getByTestId("data-table")).toContainText("Gulshan Corner Shop");
  await expect(page.getByTestId("data-table")).not.toContainText("New Corner Mart");

  // approve the closure
  await page.getByRole("row", { name: /Gulshan Corner Shop/ }).getByTestId("action-approve").click();
  await page.locator("#reason").fill("Owner confirmed the permanent closure");
  await page.getByRole("button", { name: "অনুমোদন" }).click();
  await page.waitForURL(/\/admin\/outlet-requests$/);
  expect((await stateOf()).tables.outlets.find((o: { id: number }) => o.id === 2)).toMatchObject({ status: "closed" });

  // verify the pending new outlet, then reject the info request with a reason
  await page.goto("/admin/outlet-requests?request_type=new");
  await page.getByRole("row", { name: /New Corner Mart/ }).getByTestId("action-verify").click();
  await page.locator("#f-sub_channel_id").fill("11");
  await page.locator("#reason").fill("Checked the photos and the address");
  await page.getByRole("button", { name: "যাচাই" }).click();
  await page.waitForURL(/\/admin\/outlet-requests$/);
  await page.goto("/admin/outlet-requests?request_type=info");
  await page.getByRole("row", { name: /Banani Store/ }).getByTestId("action-reject").click();
  await page.locator("#reason").fill("Not the owner of record");
  await page.getByRole("button", { name: "বাতিল", exact: true }).first().click();
  await page.waitForURL(/\/admin\/outlet-requests$/);
  const s = await stateOf();
  expect(s.tables.outletRequests.find((r: { request_uuid: string }) => r.request_uuid.startsWith("4444"))).toMatchObject({ status: "rejected", rejection_reason: "Not the owner of record" });
});

test("TSO reads the approval panel but has no actions", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await page.goto("/admin/outlet-requests");
  await expect(page.getByTestId("data-table")).toContainText("New Corner Mart");
  await expect(page.getByTestId("action-approve")).toHaveCount(0);
  await expect(page.getByTestId("action-reject")).toHaveCount(0);
});

test("wholesale marking: basket with a live count, confirm step, one audit row per outlet, idempotent retry", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.goto("/admin/wholesale-marking");
  await expect(page.getByTestId("basket-count")).toContainText("০"); // Bengali digits
  await page.getByRole("checkbox", { name: /DHK-334-001/ }).check();
  await page.getByRole("checkbox", { name: /DHK-334-003/ }).check();
  await expect(page.getByTestId("basket-count")).toContainText("২");
  await page.getByTestId("basket-continue").click();
  await expect(page.getByTestId("basket-confirm")).toBeVisible();
  await page.getByTestId("basket-submit").click();
  await expect(page.getByTestId("error-reason")).toBeVisible();
  await page.locator("#reason").fill("Quarterly wholesale review");
  await page.getByTestId("basket-submit").click();
  await expect(page.getByTestId("form-ok")).toContainText("১টি বদলেছে"); // outlet 3 was already wholesale
  const s = await stateOf();
  expect(s.audit.filter((a: { action: string }) => a.action === "outlet.outlet_kind")).toHaveLength(1);
  await expect(page.getByTestId("basket-count")).toContainText("০");
  // reloading keeps nothing selected after success
  await page.reload();
  await expect(page.getByTestId("basket-count")).toContainText("০");
});

test("support cannot reach wholesale marking", async ({ page }) => {
  await loginOk(page, "support1", "support-pass-1", "123456");
  await page.goto("/admin/wholesale-marking");
  await expect(page.getByTestId("forbidden")).toBeVisible();
});
