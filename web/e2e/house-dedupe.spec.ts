import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

test("houses: the duplicate pair is listed, one is deactivated with a reason, the group is then resolved", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/house-dedupe");
  const group = page.getByTestId("house-group");
  await expect(group).toHaveCount(1);
  await expect(page.getByTestId("house-10")).toBeVisible();
  await expect(page.getByTestId("house-20")).toBeVisible();
  await expect(group).toContainText("H-1"); // the oldest active is the suggested keeper

  await page.getByTestId("toggle-20").click();
  await page.locator("#reason").fill("Duplicate of H-1, same depot");
  await page.getByTestId("hd-apply").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  const s = await (await fetch(`${MOCK}/__mock/state`)).json();
  expect(s.audit.at(-1)).toMatchObject({ entity: "geo_node", reason: "Duplicate of H-1, same depot" });
  expect(s.audit.at(-1).after).toMatchObject({ status: "inactive" });

  await expect(page.getByTestId("hd-none")).toBeVisible(); // no open group left
  await page.getByTestId("hd-toggle").click();
  await expect(page.getByTestId("house-group")).toContainText("H-5");
  await page.getByTestId("toggle-20").click(); // and it can be activated again
  await expect(page.getByTestId("hd-confirm")).toBeVisible();
});

test("houses: SUPPORT sees the groups without actions", async ({ page }) => {
  await loginOk(page, "msupport1", "support-pass-1", "123456");
  await page.goto("/admin/house-dedupe");
  await expect(page.getByTestId("house-group")).toHaveCount(1);
  await expect(page.getByTestId("toggle-20")).toHaveCount(0);
});
