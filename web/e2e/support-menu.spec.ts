// SUPPORT lands on sync health and is refused the other dashboard pages with a real 403 (docs/24 s8.5; nothing here needs a backend change).
import { expect, test } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

test("SUPPORT lands on /sync-health, sees no report menu, and is refused the other dashboard pages", async ({ page }) => {
  await loginOk(page, "support1", "support-pass-1", "123456");
  await expect(page).toHaveURL(/\/sync-health/); // the login lands on "/", which sends SUPPORT to sync health
  await page.getByRole("link", { name: "English" }).click();
  await expect(page).toHaveURL(/\/sync-health/);
  await expect(page.locator("h1").first()).toBeVisible();
  await expect(page.getByTestId("zone-table")).toBeVisible(); // runs on the sync-health read alone
  await expect(page.getByTestId("sync-login").getByTestId("not-available")).toBeVisible();
  await expect(page.getByRole("link", { name: "Browse Retailer" })).toHaveCount(0);
  for (const p of ["/daily-tracking", "/reports/retailers", "/leave"]) {
    const res = await page.goto(p);
    expect(res?.status(), p).toBe(403);
  }
});
