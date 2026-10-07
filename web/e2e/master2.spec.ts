import { expect, test } from "@playwright/test";
import { MOCK, dhakaPlus, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});
const stateOf = async () => (await fetch(`${MOCK}/__mock/state`)).json();

test("user scope: only the role's node type, saved from a date with a reason", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/users");
  await page.getByRole("row", { name: /tso334/ }).getByTestId("link-scope").click();
  await expect(page.getByTestId("scope-user")).toContainText("tso334");
  await expect(page.getByTestId("scope-nodes")).toContainText("T-334");
  // a TSO may only hold territories
  const types = await page.getByLabel("পরিধি", { exact: true }).locator("option").allTextContents();
  expect(types).toEqual(["টেরিটরি"]);
  await page.getByLabel("নোড").selectOption({ label: "T-335 · Mirpur" });
  await page.getByTestId("scope-add").click();
  await expect(page.getByTestId("scope-nodes")).toContainText("T-335");
  await page.getByTestId("scope-save").click();
  await expect(page.getByTestId("error-reason")).toBeVisible();
  await page.locator("#reason").fill("TSO takes over Mirpur as well");
  await page.getByTestId("scope-save").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  expect((await stateOf()).custom.scopes["2001"].nodes).toHaveLength(2);
  expect((await stateOf()).audit.at(-1)).toMatchObject({ entity: "user_scope", reason: "TSO takes over Mirpur as well" });
});

test("user scope: an SR has none; support sees it read-only", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/user-scope/1001");
  await expect(page.getByTestId("no-scope")).toBeVisible();
  await page.context().clearCookies();
  await loginOk(page, "msupport1", "support-pass-1", "123456");
  await page.goto("/admin/user-scope/2001");
  await expect(page.getByTestId("scope-save")).toHaveCount(0);
});

test("sales plan: tree with select-all, apply to every zone of a territory, one audit row per zone", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/master-data");
  await page.getByTestId("link-sales-plan").click();
  await page.locator('select[name="zone_id"]').selectOption({ label: "Z-335-1 · Mirpur Zone 1" });
  await page.getByTestId("filter-bar").locator('button[type="submit"]').click();
  await expect(page.getByTestId("plan-count")).toContainText("০"); // Bengali digits, none enabled yet
  await page.getByRole("checkbox", { name: /Max Royal 10s/ }).check();
  await expect(page.getByTestId("plan-count")).toContainText("১");
  await page.getByTestId("plan-group-cigarette").getByRole("checkbox").first().check(); // select all in the group
  await expect(page.getByTestId("plan-count")).toContainText("২");
  await page.getByTestId("scope-territory").check(); // Mirpur zone 1 and zone 2 share the territory
  await page.locator("#f-valid_from").fill(dhakaPlus(1));
  await page.getByTestId("plan-save").click();
  await expect(page.getByTestId("error-reason")).toBeVisible();
  await page.locator("#reason").fill("New SKUs enabled for the whole Mirpur territory");
  await page.getByTestId("plan-save").click();
  await expect(page.getByTestId("plan-results").getByRole("status")).toHaveCount(2);
  const s = await stateOf();
  expect(s.custom.salesPlans["15"].sku_ids).toEqual([1, 2]);
  expect(s.custom.salesPlans["19"].sku_ids).toEqual([1, 2]);
  expect(s.audit.filter((a: { entity: string }) => a.entity === "sales_plan")).toHaveLength(2);
});

test("SR transfer: new primary starts and the old one ends at the same date", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/sr-transfer");
  await page.locator('select[name="user_id"]').selectOption({ label: "sr334001 · Testing Banani" });
  await page.getByTestId("filter-bar").locator('button[type="submit"]').click();
  await expect(page.getByTestId("transfer-current")).toContainText("R-334-01");
  await page.locator("#f-route").selectOption({ label: "R-335-02 · Mirpur 2F" });
  await page.locator("#f-date").fill(dhakaPlus(4));
  await page.locator("#reason").fill("Moved to Mirpur after the roster change");
  await page.getByTestId("transfer-submit").click();
  await expect(page.getByTestId("transfer-steps").getByRole("status")).toHaveCount(2);
  const a = (await stateOf()).tables.assignments;
  expect(a.find((x: { id: number }) => x.id === 1).valid_to).toBe(dhakaPlus(4));
  expect(a.some((x: { route_id: number; user_id: number; valid_from: string }) => x.route_id === 4 && x.user_id === 1001 && x.valid_from === dhakaPlus(4))).toBe(true);
});

test("prices: three decimals typed in Bengali digits, a preview is required, the edit invalidates it, publish once", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/prices");
  await expect(page.getByTestId("price-row").first()).toContainText("MaxR-10S");
  await expect(page.getByTestId("price-publish")).toBeDisabled();
  await page.getByLabel("MaxR-10S আউটলেট").fill("১২৬০.৫০৫");
  await page.getByLabel("MaxR-10S আউটলেট").fill("১২৬০.৫০৫৫"); // four decimals: refused
  await page.locator("#f-valid_from").fill(dhakaPlus(2));
  await page.locator("#reason").fill("Quarterly price revision for October");
  await page.getByTestId("price-preview").click();
  await expect(page.getByTestId("error-reason")).toHaveCount(0);
  await expect(page.getByRole("alert").first()).toBeVisible();
  await page.getByLabel("MaxR-10S আউটলেট").fill("১২৬০.৫০৫");
  await page.getByTestId("price-preview").click();
  await expect(page.getByTestId("price-preview-result")).toContainText("১"); // 1 SKU
  await expect(page.getByTestId("price-publish")).toBeEnabled();
  await page.getByLabel("MaxR-10S আউটলেট").fill("১২৬০.৫০৬"); // editing again clears the preview
  await expect(page.getByTestId("price-publish")).toBeDisabled();
  await page.getByTestId("price-preview").click();
  await page.getByTestId("price-publish").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  const s = await stateOf();
  expect(s.custom.prices.find((p: { sku_id: number; price_type: string; valid_to: string | null }) => p.sku_id === 1 && p.price_type === "outlet" && p.valid_to === null).amount_mtk).toBe(1_260_506);
  expect(s.audit.filter((a: { entity: string }) => a.entity === "price_batch")).toHaveLength(1);
});

test("prices: a large change shows the second-approver notice", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/prices");
  await page.getByLabel("MaxR-10S আউটলেট").fill("2000");
  await page.locator("#f-valid_from").fill(dhakaPlus(2));
  await page.locator("#reason").fill("Large correction after the tax change");
  await page.getByTestId("price-preview").click();
  await expect(page.getByTestId("price-needs-approval")).toBeVisible();
});
