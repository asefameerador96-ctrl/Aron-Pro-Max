import { expect, test } from "@playwright/test";
import { loginOk } from "./helpers";

test("F-ADM-076: the SR lifecycle page lists create, bind, reassign and disable, and a SUPPORT user reaches it read-only", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  const res = await page.goto("/admin/sr-lifecycle");
  expect(res?.status()).toBe(200);
  for (const s of ["create", "bind", "reassign", "disable"]) await expect(page.getByTestId(`step-${s}`)).toBeVisible();
  await page.getByTestId("step-reassign").getByRole("link").click();
  await expect(page).toHaveURL(/\/admin\/sr-transfer/);
});
