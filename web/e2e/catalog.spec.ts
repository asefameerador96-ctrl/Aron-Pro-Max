import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});
const stateOf = async () => (await fetch(`${MOCK}/__mock/state`)).json();

test("product tree: brand under a segment shows the parent label; SKU create with units and Bengali digits", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/brands");
  await expect(page.getByTestId("data-table")).toContainText("Max Royal");
  await expect(page.getByTestId("data-table")).toContainText("Premium"); // parent segment label

  await page.goto("/admin/skus/new");
  await page.locator("#f-code").fill("NEW-10S");
  await page.locator("#f-name").fill("New Brand 10s");
  await page.locator("#f-short_name").fill("New10");
  await page.locator("#f-variant_id").selectOption({ label: "Max Royal 10s" });
  await page.locator("#f-category_code").selectOption("cigarette");
  await page.locator("#f-base_unit").selectOption("stick");
  await page.locator("#f-base_per_pack").fill("10");
  await page.locator("#f-entry_unit_default").selectOption("pack");
  await page.locator("#f-report_factor").fill("০.১০০");
  await page.locator("#f-sort").fill("3");
  await page.locator("#reason").fill("Launching the new 10s pack in October");
  await page.getByRole("button", { name: "তৈরি করুন" }).click();
  await expect(page.getByTestId("data-table")).toContainText("NEW-10S");
  // the SKU's base unit is create-only: the edit form has no such control
  await page.getByRole("row", { name: /NEW-10S/ }).getByRole("link", { name: "সম্পাদনা" }).click();
  await expect(page.locator("#f-base_unit")).toHaveCount(0);
  await expect(page.locator("#f-base_per_pack")).toHaveCount(0);
});

test("classification lists: locked codes, add an item, retire another, audited with the reason", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/master-data");
  await page.getByTestId("codelist-geo_class").click();
  await expect(page.getByTestId("codelist-row")).toHaveCount(4);
  await expect(page.getByTestId("codelist-row").first().getByLabel("কোড")).toHaveAttribute("readonly", "");

  await page.getByTestId("codelist-add").click();
  const row = page.getByTestId("codelist-row").nth(4);
  await row.getByLabel("কোড").fill("char_land");
  await row.getByLabel("ইংরেজি লেবেল").fill("River char");
  await row.getByLabel("বাংলা লেবেল").fill("চর");
  await page.getByTestId("codelist-row").nth(3).getByLabel("বন্ধের তারিখ").fill("2026-12-31");
  await page.getByTestId("codelist-save").click();
  await expect(page.getByTestId("error-reason")).toBeVisible();
  await page.locator("#reason").fill("Adding river chars and retiring hill");
  await page.getByTestId("codelist-save").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  expect((await stateOf()).audit.at(-1)).toMatchObject({ entity: "code_list", reason: "Adding river chars and retiring hill" });
  await page.reload();
  await expect(page.getByTestId("codelist-row")).toHaveCount(5);
});

test("code list: a bad code is flagged on its row and nothing is saved", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/code-lists/channel");
  await page.getByTestId("codelist-add").click();
  const row = page.getByTestId("codelist-row").nth(2);
  await row.getByLabel("কোড").fill("Bad Code");
  await row.getByLabel("ইংরেজি লেবেল").fill("Label");
  await page.locator("#reason").fill("Trying a bad code value");
  await page.getByTestId("codelist-save").click();
  await expect(row.getByRole("alert")).toContainText("সারি ৩");
  expect((await stateOf()).audit).toHaveLength(0);
});

test("support sees the code lists read-only", async ({ page }) => {
  await loginOk(page, "msupport1", "support-pass-1", "123456");
  await page.goto("/admin/code-lists/channel");
  await expect(page.getByTestId("codelist-save")).toHaveCount(0);
  await expect(page.getByText("আপনি এই তালিকা দেখতে পারবেন, বদলাতে পারবেন না।")).toBeVisible();
});
