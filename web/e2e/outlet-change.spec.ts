import { expect, test, type Page } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});
const state = async () => (await fetch(`${MOCK}/__mock/state`)).json();

async function openOutlet(page: Page, query: string, id: number) {
  await page.goto("/admin/outlet-change");
  await page.getByTestId("och-search").locator('input[name="q"]').fill(query);
  await page.getByTestId("och-search").locator('button[type="submit"]').click();
  await page.getByTestId(`och-pick-${id}`).click();
  await expect(page.getByTestId("outlet-change-form")).toBeVisible();
}

test("F-WEB-003: a TSO proposes a new owner name; a pending request is created with the reason as its note", async ({ page }) => {
  await loginOk(page, "mtso1", "tso-pass-1");
  await openOutlet(page, "Banani", 1);
  await page.locator("#f-owner_name").fill("Md. Rahim Uddin");
  await page.locator("#reason").fill("Owner told me the shop changed hands");
  await page.getByTestId("och-submit").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  const s = await state();
  const row = s.tables.outletRequests.at(-1);
  expect(row).toMatchObject({ request_type: "info", status: "pending", outlet_id: 1, proposed: { owner_name: "Md. Rahim Uddin" }, note: "Owner told me the shop changed hands" });
  expect(s.audit.at(-1)).toMatchObject({ entity: "outlet_request", reason: "Owner told me the shop changed hands" });
});

test("a TSO only finds outlets in its own reach", async ({ page }) => {
  await loginOk(page, "mtso1", "tso-pass-1");
  await page.goto("/admin/outlet-change?q=Mirpur");
  await expect(page.getByTestId("och-none")).toBeVisible();
});

test("nothing different from the outlet is refused before the call", async ({ page }) => {
  await loginOk(page, "mtso1", "tso-pass-1");
  await openOutlet(page, "Banani", 1);
  await page.locator("#reason").fill("No actual difference here");
  await page.getByTestId("och-submit").click();
  await expect(page.getByTestId("och-no-change")).toBeVisible();
  expect((await state()).tables.outletRequests).toHaveLength(4);
});

test("a DMO proposes a corrected location and a closure", async ({ page }) => {
  await loginOk(page, "mdmo1", "dmo-pass-2");
  await openOutlet(page, "Mirpur", 3);
  await page.locator("#f-request_type").selectOption("location");
  await page.locator("#f-lat").fill("23.80");
  await page.locator("#f-lng").fill("90.36");
  await page.locator("#reason").fill("Pin is on the wrong street");
  await page.getByTestId("och-submit").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  await page.locator("#f-request_type").selectOption("close");
  await page.locator("#f-close_reason_code").selectOption({ index: 1 });
  await page.locator("#reason").fill("The shop has closed for good");
  await page.getByTestId("och-submit").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  const rows = (await state()).tables.outletRequests;
  expect(rows.at(-2)).toMatchObject({ request_type: "location", proposed: { lat: 23.8, lng: 90.36 } });
  expect(rows.at(-1)).toMatchObject({ request_type: "close", proposed: { close_reason_code: "permanent" } });
});

test("administrators and support edit directly or read only: the proposal page is forbidden", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/outlet-change");
  await expect(page.getByTestId("och-search")).toHaveCount(0);
  await page.context().clearCookies();
  await loginOk(page, "msupport1", "support-pass-1", "123456");
  await page.goto("/admin/outlet-change");
  await expect(page.getByTestId("och-search")).toHaveCount(0);
});
