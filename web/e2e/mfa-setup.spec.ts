import { expect, test } from "@playwright/test";
import { resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

test("a new administrator sets up an authenticator at the second step, keeps the recovery codes, and signs in", async ({ page }) => {
  await page.goto("/login");
  await page.locator('input[name="username"]').fill("newadmin1");
  await page.locator('input[name="password"]').fill("newadmin-pass-1");
  await page.locator('form button[type="submit"]').first().click();
  await expect(page.getByTestId("mfa-form")).toBeVisible();
  await page.getByTestId("mfa-setup").click();
  await expect(page.getByTestId("qr-svg")).toBeVisible();
  await expect(page.getByTestId("mfa-secret")).toHaveText("JBSWY3DPEHPK3PXP");
  await expect(page.getByTestId("mfa-recovery-codes").locator("li")).toHaveCount(10);
  await page.locator('input[name="code"]').fill("123456");
  await page.getByTestId("mfa-form").locator('button[type="submit"]').click();
  await page.waitForURL((u) => !u.pathname.startsWith("/login"));
});
