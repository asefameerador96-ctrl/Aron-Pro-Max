import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

test("F-TSO-023: a TSO resets an SR's password (shown once) and unlocks another; users of other zones are not listed", async ({ page }) => {
  await loginOk(page, "mtso1", "tso-pass-1");
  await page.goto("/admin/team");
  await expect(page.getByTestId("data-table")).toContainText("sr334001");
  await expect(page.getByTestId("data-table")).not.toContainText("sr335001"); // another zone
  await page.getByTestId("reset_password-1001").click();
  await page.locator("#reason").fill("Phone was replaced");
  await page.getByTestId("team-submit").click();
  await expect(page.getByTestId("team-password")).toContainText("Tmp-1001");
  await page.getByTestId("unlock-1002").click();
  await expect(page.getByTestId("team-password")).toHaveCount(0); // gone once the next action starts
  await page.locator("#reason").fill("Locked after wrong tries");
  await page.getByTestId("team-submit").click();
  await expect(page.getByTestId("team-done")).toBeVisible();
  const s = await (await fetch(`${MOCK}/__mock/state`)).json();
  expect(s.audit.slice(-2).map((a: { action: string }) => a.action)).toEqual(["user.reset_password", "user.unlock"]);
});

test("a TSO cannot open the users table", async ({ page }) => {
  await loginOk(page, "mtso1", "tso-pass-1");
  const res = await page.goto("/admin/users");
  expect(res?.status()).toBe(403);
});
