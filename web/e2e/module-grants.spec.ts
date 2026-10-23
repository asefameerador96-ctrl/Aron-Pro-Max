import { expect, test } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

// D-WEB-ADMIN-MENU-01: the sidebar mirrors the ADMIN's module claim (targets r, masterdata r, outlets w).
test("modules: the menu shows only granted modules; targets at read hides the upload; the hub hides modules without a grant", async ({ page }) => {
  await loginOk(page, "mgrant1", "grant-pass-1", "123456");
  await page.goto("/admin");
  const nav = page.locator("nav").first();
  await expect(nav.locator('a[href="/admin/master-data"]').first()).toBeVisible();
  await expect(nav.locator('a[href="/admin/outlet-requests"]').first()).toBeVisible();
  await expect(page.locator('a[href="/admin/mdm"]')).toHaveCount(0);
  await expect(page.locator('a[href="/admin/devices"]')).toHaveCount(0);

  await page.goto("/admin/route-targets");
  await expect(page.getByTestId("module-read-only")).toBeVisible();
  await expect(page.getByTestId("rt-upload")).toHaveCount(0);
  await expect(page.getByTestId("data-table")).toBeVisible(); // read shows the existing targets

  await page.goto("/admin/master-data");
  await expect(page.getByTestId("link-calendar")).toBeVisible(); // masterdata: r
  await expect(page.getByTestId("link-prices")).toHaveCount(0); // prices: no grant
  await expect(page.getByTestId("link-route-targets")).toBeVisible(); // targets: r

  await page.goto("/admin/outlet-import");
  await expect(page.getByTestId("oi-upload")).toBeVisible(); // outlets: w
});

// Non-generator pages hide their write controls from an ADMIN whose grant for the owning module is read (masterdata: r).
test("modules: the calendar of a masterdata reader has no holiday form", async ({ page }) => {
  await loginOk(page, "mgrant1", "grant-pass-1", "123456");
  await page.goto("/admin/calendar");
  await expect(page.getByTestId("module-read-only")).toBeVisible();
  await expect(page.getByTestId("holiday-form")).toHaveCount(0);
  await expect(page.getByTestId("weekend-form")).toHaveCount(0);
});
