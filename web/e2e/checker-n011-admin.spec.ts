import { expect, test } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

// PageCursor is up to 512 chars (contract). The list page must forward it unchanged.
// Cursor here is base64url of 80 spaces + "3": the mock reads it as offset 3 (Number() trims the spaces), so 4 of the 7 seeded rows show.
// A cursor cut to 80 characters decodes to spaces only (offset 0) and shows all 7.
test("N011-C1: a long (valid) pagination cursor is not truncated", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  const cursor = Buffer.from(" ".repeat(80) + "3").toString("base64url");
  expect(cursor.length).toBeGreaterThan(80);
  expect(cursor.length).toBeLessThanOrEqual(512);
  await page.goto(`/admin/clusters?cursor=${cursor}`);
  await expect(page.getByTestId("data-table")).toBeVisible();
  await expect(page.getByTestId("data-table").locator("tbody tr")).toHaveCount(4);
});

// After a successful save the form keeps comparing against the INITIAL values, so reverting a field to its original value is "no changes".
test("N011-C2: edit, save, then change the field back to its original value is a real change", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.goto("/admin/clusters/1");
  const name = page.locator("#f-name");
  const original = await name.inputValue();
  await name.fill("Temporary Name X");
  await page.locator("#reason").fill("First rename for the test");
  await page.getByRole("button", { name: "সংরক্ষণ" }).click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  await name.fill(original);
  await page.locator("#reason").fill("Revert the rename now");
  await page.getByRole("button", { name: "সংরক্ষণ" }).click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  await expect(page.getByTestId("form-error")).toHaveCount(0);
});

// SUPPORT is a portal role but cannot write: the write pages answer "forbidden"; the HTTP status should say so too.
test("N011-C3: SUPPORT opening the create / edit page gets HTTP 403, not 200 with a forbidden body", async ({ page }) => {
  await loginOk(page, "support1", "support-pass-1", "123456");
  const create = await page.goto("/admin/clusters/new");
  await expect(page.getByTestId("forbidden")).toBeVisible();
  expect(create?.status()).toBe(403);
  const edit = await page.goto("/admin/clusters/1");
  await expect(page.getByTestId("forbidden")).toBeVisible();
  expect(edit?.status()).toBe(403);
});
