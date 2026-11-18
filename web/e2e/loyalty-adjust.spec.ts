// Points adjustment of a programme (portal-loyalty, contract 1.23.0): the single form, the CSV upload with its dry-run preview then apply, the ledger view,
// role checks (a read-only grant sees the ledger and no form), and the Bangla screen. Seed: programme 1 ASTHA-Q4 (active), 3 SS-NOV (draft).
import { expect, test, type Page } from "@playwright/test";
import { hydrated, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

const english = async (page: Page) => { await page.getByRole("link", { name: "English" }).click(); await page.waitForLoadState("networkidle"); };
const superEnglish = async (page: Page) => { await loginOk(page, "super1", "super-pass-1", "123456"); await english(page); };
const csv = (text: string) => ({ name: "points.csv", mimeType: "text/csv", buffer: Buffer.from(text) });

test("single adjustment: errors sit beside their fields, a valid post shows in the ledger with its reason, an overdraw needs the box", async ({ page }) => {
  await superEnglish(page);
  await page.goto("/admin/loyalty/1");
  await hydrated(page.getByTestId("adjust-form"));
  await page.locator("#adj-outlet").fill("0");
  await page.locator("#adj-points").fill("0");
  await page.locator("#adj-reason").fill("short");
  await page.getByTestId("adj-submit").click();
  await expect(page.getByTestId("error-adj-outlet")).toBeVisible();
  await expect(page.getByTestId("error-adj-points")).toBeVisible();
  await expect(page.getByTestId("error-adj-reason")).toBeVisible();
  await page.locator("#adj-outlet").fill("101");
  await page.locator("#adj-points").fill("50");
  await page.locator("#adj-reason").fill("Opening balance loaded by the office");
  await page.getByTestId("adj-submit").click();
  await expect(page.getByTestId("adjust-banner")).toContainText("posted");
  await expect(page.getByTestId("programme-ledger")).toContainText("#101");
  await expect(page.getByTestId("programme-ledger")).toContainText("+50");
  await expect(page.getByTestId("programme-ledger")).toContainText("Opening balance loaded by the office");
  // A debit past zero is refused until the box is ticked.
  await page.locator("#adj-outlet").fill("101");
  await page.locator("#adj-points").fill("-80");
  await page.locator("#adj-reason").fill("Correction of a wrong load");
  await page.getByTestId("adj-submit").click();
  await expect(page.getByTestId("adjust-banner")).toContainText("below zero");
  await page.getByTestId("adj-overdraw").check();
  await page.getByTestId("adj-submit").click();
  await expect(page.getByTestId("programme-ledger")).toContainText("-80");
});

test("bulk upload: a bad file is shown by line, a clean file is previewed (nothing saved) and then applied once", async ({ page }) => {
  await superEnglish(page);
  await page.goto("/admin/loyalty/1");
  await hydrated(page.getByTestId("adjust-tab-bulk"));
  await page.getByTestId("adjust-tab-bulk").click();
  await hydrated(page.getByTestId("adj-file"));
  await page.getByTestId("adj-file").setInputFiles(csv("outlet_id,points\n101,10\n102,0\nabc,5\n"));
  await expect(page.getByTestId("adj-file-errors")).toContainText("Zero adjusts nothing");
  await page.getByTestId("adj-file").setInputFiles(csv("outlet_id,points,expires_on\n101,100,2099-12-31\n102,40\n"));
  await expect(page.getByTestId("adj-file-name")).toBeVisible();
  await expect(page.getByTestId("adj-file-errors")).toHaveCount(0);
  await page.locator("#adj-bulk-reason").fill("Bulk load of the October credit");
  await expect(page.getByTestId("adj-apply")).toBeDisabled();       // no apply before a clean preview
  await page.getByTestId("adj-preview").click();
  await expect(page.getByTestId("adj-preview-ok")).toContainText("2 rows");
  await expect(page.getByTestId("programme-ledger")).not.toContainText("#101"); // the preview saved nothing
  await page.getByTestId("adj-apply").click();
  await expect(page.getByTestId("adjust-banner")).toContainText("2 rows posted");
  await expect(page.getByTestId("programme-ledger")).toContainText("#101");
  await expect(page.getByTestId("programme-ledger")).toContainText("+100");
  await expect(page.getByTestId("programme-ledger")).toContainText("Bulk load of the October credit");
});

test("a debit file past zero shows would-overdraw rows in the preview and cannot be applied", async ({ page }) => {
  await superEnglish(page);
  await page.goto("/admin/loyalty/1");
  await hydrated(page.getByTestId("adjust-tab-bulk"));
  await page.getByTestId("adjust-tab-bulk").click();
  await hydrated(page.getByTestId("adj-file"));
  await page.getByTestId("adj-file").setInputFiles(csv("103,-5\n"));
  await page.locator("#adj-bulk-reason").fill("Correction of a wrong load");
  await page.getByTestId("adj-preview").click();
  await expect(page.getByTestId("adj-preview-bad")).toBeVisible();
  await expect(page.getByTestId("adj-preview-errors")).toContainText("below zero");
  await expect(page.getByTestId("adj-apply")).toBeDisabled();
});

test("roles: a read-only grant sees the ledger and no form; a draft programme offers no adjustment; no grant sees neither", async ({ page, context }) => {
  await loginOk(page, "mloy1", "loy-pass-1", "123456");
  await page.goto("/admin/loyalty/1");
  await expect(page.getByTestId("programme-ledger")).toBeVisible();
  await expect(page.getByTestId("programme-adjust")).toHaveCount(0);
  await context.clearCookies();
  await superEnglish(page);
  await page.goto("/admin/loyalty/3");
  await expect(page.getByTestId("adjust-inactive")).toBeVisible();
  await context.clearCookies();
  await loginOk(page, "mgrant1", "grant-pass-1", "123456");
  await page.goto("/admin/loyalty/1");
  await expect(page.getByTestId("programme-ledger")).toHaveCount(0);
  await expect(page.getByTestId("programme-adjust")).toHaveCount(0);
});

test("Bangla: the adjustment form and the ledger are in Bangla with no horizontal overflow", async ({ page }) => {
  await loginOk(page, "super1", "super-pass-1", "123456");
  await page.setViewportSize({ width: 390, height: 800 });
  await page.goto("/admin/loyalty/1");
  await hydrated(page.getByTestId("adjust-form"));
  await expect(page.getByTestId("programme-adjust")).toContainText("পয়েন্ট সমন্বয়");
  await expect(page.getByTestId("programme-ledger")).toContainText("পয়েন্ট খতিয়ান");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
});
