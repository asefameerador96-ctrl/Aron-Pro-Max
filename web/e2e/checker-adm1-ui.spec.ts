// Checker ADM-1 (F-ADM-001/002/003/007): browser-level checks of the write pages, the one-time password and a11y basics.
import { expect, test } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

test("CE1: SUPPORT gets HTTP 403 on write pages even when the id or slug is percent-encoded", async ({ page }) => {
  await loginOk(page, "support1", "support-pass-1", "123456");
  // /admin/clusters/1 is 403 (N011-C3); the proxy matches the raw path with [0-9]+, so %31 skips its write-page gate.
  const encodedId = await page.goto("/admin/clusters/%31");
  expect(encodedId?.status()).toBe(403);
  const encodedSlug = await page.goto("/admin/%63lusters/new");
  expect(encodedSlug?.status()).toBe(403);
});

test("CE2: the one-time panel shows localised labels and a localised expiry, not raw member names and an ISO string", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.goto("/admin/users/new");
  await page.locator("#f-username").fill("chk001");
  await page.locator("#f-full_name").fill("Checker Rep");
  await page.locator("#reason").fill("Checker creates a user for the panel");
  await page.getByRole("button", { name: "তৈরি করুন" }).click();
  const panel = page.getByTestId("shown-once");
  await expect(panel).toBeVisible();
  const text = (await panel.innerText()) ?? "";
  expect(text).not.toContain("temporary_password");
  expect(text).not.toMatch(/\d{4}-\d{2}-\d{2}T/);
});

test("CE4: a required field is exposed to assistive technology as required, not only with a visual asterisk", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.goto("/admin/routes/new");
  const name = page.locator("#f-name");
  const required = (await name.getAttribute("required")) !== null || (await name.getAttribute("aria-required")) === "true";
  expect(required).toBe(true);
});

test("CE5: an entity with no edit (route assignments) answers 404 on its edit URL, not a 'forbidden' page", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  const res = await page.goto("/admin/route-assignments/1");
  expect(res?.status()).toBe(404);
});
