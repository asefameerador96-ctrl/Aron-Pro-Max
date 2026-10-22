import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

const stateOf = async () => (await fetch(`${MOCK}/__mock/state`)).json();

test("users: the role select lists the seven portal levels first and marks SR and AMO as app only", async ({ page }) => {
  await loginOk(page, "msuper1", "super-pass-1", "123456");
  await page.goto("/admin/users/new");
  const labels = await page.locator("#f-role option").allTextContents();
  const values = await page.locator("#f-role option").evaluateAll((o) => o.map((x) => (x as HTMLOptionElement).value).filter(Boolean));
  expect(values.slice(0, 7)).toEqual(["SUPERADMIN", "ADMIN", "NATIONAL", "BRAND", "WM", "DMO", "TSO"]);
  expect(values).toContain("SR");
  expect(labels.join("|")).toMatch(/শুধু অ্যাপ/);
});

test("access: Super Admin grants modules to an ADMIN with a reason and If-Match; the change is audited and a second save uses the new version", async ({ page }) => {
  await loginOk(page, "msuper1", "super-pass-1", "123456");
  await page.goto("/admin/users");
  await expect(page.getByRole("row", { name: /admin1/ }).getByTestId("link-access")).toBeVisible();
  await expect(page.getByRole("row", { name: /sr334001/ }).getByTestId("link-access")).toHaveCount(0);
  await page.getByRole("row", { name: /admin1/ }).getByTestId("link-access").click();

  await expect(page.getByTestId("access-editor")).toBeVisible();
  await expect(page.getByTestId("grant-outlets").locator('input[value="w"]')).toBeChecked();
  await expect(page.getByTestId("grant-prices").locator('input[value="none"]')).toBeChecked();
  await page.getByTestId("grant-prices").locator('input[value="r"]').check();
  await page.getByTestId("grant-outlets").locator('input[value="none"]').check();
  await page.locator("#reason").fill("Prices read for the quarterly review");
  await page.getByTestId("access-save").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  const s = await stateOf();
  expect(s.audit.at(-1)).toMatchObject({ entity: "user_access", reason: "Prices read for the quarterly review" });
  expect(s.custom.access["3001"]).toMatchObject({ version: 3, modules: { reports: "r", prices: "r" } });

  // router.refresh loaded version 3: the next save sends it
  await page.getByTestId("grant-masterdata").locator('input[value="r"]').check();
  await page.locator("#reason").fill("A second change with a stale version");
  await page.getByTestId("access-save").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  expect((await stateOf()).custom.access["3001"].version).toBe(4);
});

test("access: a brand user keeps at least one brand", async ({ page }) => {
  await loginOk(page, "msuper1", "super-pass-1", "123456");
  await page.goto("/admin/user-access/5002");
  await expect(page.getByTestId("brand-5")).toBeChecked();
  await page.getByTestId("brand-5").uncheck();
  await page.locator("#reason").fill("Removing the only brand to test the guard");
  await page.getByTestId("access-save").click();
  await expect(page.getByTestId("error-brands")).toBeVisible();
  await page.getByTestId("brand-6").check();
  await page.getByTestId("access-save").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  expect((await stateOf()).custom.access["5002"]).toMatchObject({ brand_ids: [6] });
});

test("access: an Administrator sees the grants read-only and no access link on the users list", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/users");
  await expect(page.getByTestId("link-access")).toHaveCount(0);
  await page.goto("/admin/user-access/3001");
  await expect(page.getByTestId("access-editor")).toBeVisible();
  await expect(page.getByTestId("access-save")).toHaveCount(0);
  await expect(page.getByTestId("grant-outlets").locator('input[value="w"]')).toBeDisabled();
});

test("access: a role without grants says so", async ({ page }) => {
  await loginOk(page, "msuper1", "super-pass-1", "123456");
  await page.goto("/admin/user-access/2001");
  await expect(page.getByTestId("access-none")).toBeVisible();
});

test("access: granting Users write and Credentials write shows a warning and still saves", async ({ page }) => {
  await loginOk(page, "msuper1", "super-pass-1", "123456");
  await page.goto("/admin/user-access/3001");
  await expect(page.getByTestId("access-creds-warning")).toHaveCount(0);
  await page.getByTestId("grant-users").locator('input[value="w"]').check();
  await expect(page.getByTestId("access-creds-warning")).toHaveCount(0);
  await page.getByTestId("grant-credentials").locator('input[value="w"]').check();
  await expect(page.getByTestId("access-creds-warning")).toBeVisible();
  await page.locator("#reason").fill("Support lead needs both for onboarding");
  await page.getByTestId("access-save").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
});
