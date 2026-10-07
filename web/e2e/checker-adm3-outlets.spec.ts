// Checker ADM-3: browser-level defects of the outlet approval panel and the wholesale basket. A failing test is a confirmed defect.
import { expect, test } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

const latin = (s: string) => Number(s.replace(/[০-৯]/g, (d) => String("০১২৩৪৫৬৭৮৯".indexOf(d))).replace(/\D/g, ""));

test("CB3-E1: a DMO opens the approval panel without a reference-list failure banner", async ({ page }) => {
  await loginOk(page, "dmo1", "dmo-pass-1");
  await page.goto("/admin/outlet-requests");
  await expect(page.getByTestId("data-table")).toContainText("New Corner Mart");
  // The requester column and the zone filter are filled from /v1/admin/users and /v1/admin/geo/zone (portal-only, 403 for a DMO in the contract mock):
  // the panel works but shouts a red error on every load.
  await expect(page.getByTestId("ref-error")).toHaveCount(0);
});

test("CB3-E2: a DMO asking for the create page of a read-only entity gets a real HTTP 403, not a 200 with a forbidden body", async ({ page }) => {
  await loginOk(page, "dmo1", "dmo-pass-1");
  const res = await page.goto("/admin/outlet-requests/new");
  expect(res?.status()).toBe(403);
});

test("CC3-E1: the wholesale confirm dialog takes keyboard focus when it opens", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/wholesale-marking");
  await page.getByRole("checkbox", { name: /DHK-334-001/ }).check();
  await page.getByTestId("basket-continue").click();
  await expect(page.getByTestId("basket-confirm")).toBeVisible();
  // role="dialog" without moving focus leaves keyboard and screen-reader users on the button behind it.
  const inside = await page.evaluate(() => document.querySelector('[data-testid="basket-confirm"]')?.contains(document.activeElement) ?? false);
  expect(inside).toBe(true);
});

test("CC3-E2: the basket never grows past the 5000 outlets the contract accepts", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.addInitScript(() => sessionStorage.setItem("aron.wholesale.basket", JSON.stringify(Array.from({ length: 4999 }, (_, i) => 100000 + i))));
  await page.goto("/admin/wholesale-marking");
  await expect(page.getByTestId("basket-count")).toContainText("৪,৯৯৯");
  await page.getByRole("checkbox", { name: "দেখানো সবগুলো বাছুন" }).check();
  // select-all adds the shown rows without the MAX check that a single tick has: the basket ends above 5000 and the submit is a bare 400.
  const n = latin((await page.getByTestId("basket-count").textContent()) ?? "");
  expect(n).toBeLessThanOrEqual(5000);
});
