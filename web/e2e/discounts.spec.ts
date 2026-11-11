// Discount programmes (portal-discount-web, D-DISC-WEB-01): gating by role and grant, list, detail with versions, create a draft,
// edit it (a new version), activate (Super Admin) and end it, each with its reason, plus the empty, error, offline and long-Bangla states.
import { expect, test, type Page } from "@playwright/test";
import { MOCK, hydrated, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

// Login stores the user's own language (Bangla); the text assertions read English, so these switch after login.
const english = async (page: Page) => { await page.getByRole("link", { name: "English" }).click(); await page.waitForLoadState("networkidle"); };
const superLogin = (page: Page) => loginOk(page, "super1", "super-pass-1", "123456");
const superEnglish = async (page: Page) => { await superLogin(page); await english(page); };
const REASON = "Owner asked for this programme";

test("gating: Super Admin sees the menu entry, the list and the New button", async ({ page }) => {
  await superLogin(page);
  await page.goto("/admin");
  await expect(page.locator('nav a[href="/admin/discounts"]').first()).toBeVisible();
  await page.goto("/admin/discounts");
  await expect(page.getByTestId("discounts-page")).toBeVisible();
  await expect(page.getByTestId("offer-new")).toBeVisible();
});

test("gating: an ADMIN with discounts at read sees the list without the write controls; no grant hides the menu and refuses the URL", async ({ page, context }) => {
  await loginOk(page, "mdisc1", "disc-pass-1", "123456");
  await page.goto("/admin/discounts");
  await expect(page.getByTestId("discounts-page")).toBeVisible();
  await expect(page.getByTestId("module-read-only")).toBeVisible();
  await expect(page.getByTestId("offer-new")).toHaveCount(0);
  await page.getByTestId("offer-link-MAXR-PKT").click();
  await expect(page.getByTestId("discount-detail-page")).toBeVisible();
  await expect(page.getByTestId("offer-edit")).toHaveCount(0);
  await expect(page.getByTestId("offer-status-panel")).toHaveCount(0);
  await page.goto("/admin/discounts/new");
  await expect(page.getByTestId("offer-form")).toHaveCount(0); // not granted for write: the not-granted page
  await context.clearCookies();
  await loginOk(page, "mgrant1", "grant-pass-1", "123456");
  await page.goto("/admin");
  await expect(page.locator('a[href="/admin/discounts"]')).toHaveCount(0);
  await page.goto("/admin/discounts");
  await expect(page.getByTestId("discounts-page")).toHaveCount(0);
  await expect(page.getByTestId("offer-link-MAXR-PKT")).toHaveCount(0);
});

test("gating: a TSO cannot open the section", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await page.goto("/admin/discounts");
  await expect(page.getByTestId("discounts-page")).toHaveCount(0);
  await expect(page.getByTestId("offer-link-MAXR-PKT")).toHaveCount(0);
});

test("list: shows the seeded programmes with value, status, filter by status and by text, and the field unit", async ({ page }) => {
  await superEnglish(page);
  await page.goto("/admin/discounts");
  const rows = page.getByTestId("data-table").locator("tbody tr");
  await expect(rows).toHaveCount(4);
  await expect(page.getByTestId("offer-status-MAXR-PKT")).toContainText("Draft");
  await expect(page.getByTestId("offer-status-RAMADAN-PO")).toContainText("Active");
  await expect(page.getByTestId("offer-status-BUY3-GET1")).toContainText("Ended");
  await expect(rows.filter({ hasText: "RAMADAN-PO" })).toContainText("2.50% off");
  await expect(rows.filter({ hasText: "MAXR-PKT" })).toContainText("per packet");
  await page.goto("/admin/discounts?status=draft");
  await expect(rows).toHaveCount(2);
  await page.goto("/admin/discounts?q=ramadan");
  await expect(rows).toHaveCount(1);
  await page.goto("/admin/discounts?q=nothing-like-this");
  await expect(page.getByTestId("table-empty")).toBeVisible();
});

test("detail: current rule in the field unit with the base figure, products, scope, versions and the audit trail", async ({ page }) => {
  await superEnglish(page);
  await page.goto("/admin/discounts");
  await hydrated(page.getByTestId("offer-link-BUY3-GET1")); // a click or fill before React attaches is lost or reverted (D-WEB-FIX-01)
  await page.getByTestId("offer-link-BUY3-GET1").click();
  await expect(page.getByTestId("offer-value")).toContainText("Buy 3 packet(s) (60 stick(s)), get 1 packet(s) (20 stick(s)) free");
  await expect(page.getByTestId("offer-version")).toHaveCount(1);
  await expect(page.getByTestId("offer-scope")).toContainText("Everywhere");
  await expect(page.getByTestId("audit-history")).toBeVisible();
  await expect(page.getByTestId("offer-status-panel")).toHaveCount(0); // ended: no move left
  await page.goto("/admin/discounts/9999");
  await expect(page.getByTestId("discount-missing")).toBeVisible();
});

test("create a draft, edit it into version 2, activate it as Super Admin with a reason, then end it", async ({ page }) => {
  await superEnglish(page);
  await page.goto("/admin/discounts/new");
  const form = page.getByTestId("offer-form");
  await hydrated(form);
  await page.getByTestId("of-submit").click();
  await expect(page.getByTestId("error-of-code")).toBeVisible(); // required fields are named, nothing is sent
  await expect(page.getByTestId("of-products-all")).toBeVisible(); // no product row: an explicit all-products warning
  await page.locator("#of-code").fill("NEW-E2E");
  await page.locator("#of-title-en").fill("E2E price off");
  await page.locator("#of-title-bn").fill("ই-টু-ই ছাড়");
  await page.locator("#of-from").fill("2026-11-15");
  await page.locator("#of-to").fill("2099-12-31");
  await page.locator("#of-plevel").selectOption("sku");
  await page.locator("#of-pitem").selectOption({ index: 2 }); // Max Royal 20s: a packet of 20
  await page.getByTestId("of-add-product").click();
  await expect(page.getByTestId("of-products")).toBeVisible();
  await expect(page.getByTestId("of-products-all")).toHaveCount(0);
  await page.locator("#of-per-unit").fill("0.0005"); // finer than a milli-taka
  await page.locator("#of-reason").fill(REASON);
  await page.getByTestId("of-submit").click();
  await expect(page.getByTestId("error-of-per-unit")).toBeVisible();
  await page.locator("#of-per-unit").fill("2"); // taka per packet of 20 -> 100 milli-taka per stick
  await page.getByTestId("of-submit").click();
  await expect(page.getByTestId("discount-detail-page")).toBeVisible();
  await expect(page.getByTestId("offer-saved")).toBeVisible();
  await expect(page.getByTestId("offer-status")).toContainText("Draft");
  await expect(page.getByTestId("offer-value")).toContainText("Tk 2.000 off per packet");

  await page.getByTestId("offer-edit").click();
  await hydrated(page.getByTestId("offer-form"));
  await expect(page.locator("#of-per-unit")).toHaveValue("2");
  await expect(page.locator("#of-code")).toBeDisabled();
  await page.locator("#of-per-unit").fill("3");
  await page.locator("#of-reason").fill("Raise the amount off to three taka");
  await page.getByTestId("of-submit").click();
  await expect(page.getByTestId("offer-value")).toContainText("Tk 3.000 off per packet");
  await expect(page.getByTestId("offer-version")).toHaveCount(2);

  await page.getByTestId("os-active").click();
  await page.getByTestId("os-confirm").click();
  await expect(page.getByTestId("error-os-reason")).toBeVisible(); // the reason is mandatory
  await page.locator("#os-reason").fill("Go live for the Ramadan week");
  await page.getByTestId("os-confirm").click();
  await expect(page.getByTestId("offer-status")).toContainText("Active");
  await expect(page.getByTestId("offer-edit")).toHaveCount(0);
  await page.getByTestId("os-inactive").click();
  await page.locator("#os-reason").fill("Programme ended by the owner");
  await page.getByTestId("os-confirm").click();
  await expect(page.getByTestId("offer-status")).toContainText("Ended");
  await expect(page.getByTestId("audit-entry").first()).toBeVisible();
});

test("an ADMIN with write cannot activate a draft (Super Admin only) but the draft can still be edited", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/discounts");
  await hydrated(page.getByTestId("offer-link-MAXR-PKT")); // a click or fill before React attaches is lost or reverted (D-WEB-FIX-01)
  await page.getByTestId("offer-link-MAXR-PKT").click();
  await expect(page.getByTestId("os-super-only")).toBeVisible();
  await expect(page.getByTestId("os-active")).toHaveCount(0);
  await expect(page.getByTestId("offer-edit")).toBeVisible();
});

test("states: the server failing shows the error banner with a reload link; offline text for 5xx", async ({ page }) => {
  await superLogin(page);
  await fetch(`${MOCK}/__mock/fail`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ prefix: "/v1/admin/offers" }) }).catch(() => undefined);
  await page.goto("/admin/discounts");
  await expect(page.getByTestId("discounts-error")).toBeVisible();
  await expect(page.getByTestId("discounts-error")).toContainText(/Reload|রিলোড/);
});

for (const [name, size] of [["phone", { width: 360, height: 740 }], ["laptop", { width: 1366, height: 768 }]] as const) {
  test(`long Bangla: the list, detail and form stay inside the page (${name})`, async ({ page }) => {
    await superLogin(page); // Bangla is the user's own language; the sign-in header shows "who" only on a wide window, so resize after it
    await page.setViewportSize(size);
    for (const path of ["/admin/discounts", "/admin/discounts/2", "/admin/discounts/2/edit", "/admin/discounts/new"]) {
      await page.goto(path);
      await expect(page.locator("h1").first()).toBeVisible();
      await page.waitForLoadState("networkidle");
      const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(over, `${path} overflows by ${over}px`).toBeLessThanOrEqual(1);
    }
    await page.goto("/admin/discounts/2"); // the open status panel (reason field and confirm step) in Bangla
    await hydrated(page.getByTestId("offer-status-panel"));
    await page.getByTestId("os-active").click();
    await expect(page.getByTestId("os-form")).toBeVisible();
    const overPanel = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overPanel, `open status panel overflows by ${overPanel}px`).toBeLessThanOrEqual(1);
  });
}

test("an offer with no product rows is saved and shown as applying to all products", async ({ page }) => {
  await superLogin(page);
  await page.goto("/admin/discounts/new");
  await hydrated(page.getByTestId("offer-form"));
  await hydrated(page.locator("#of-code")); // a click or fill before React attaches is lost or reverted (D-WEB-FIX-01)
  await page.locator("#of-code").fill("ALL-E2E");
  await page.locator("#of-title-en").fill("All products offer");
  await page.locator("#of-type").selectOption("pct_discount");
  await page.locator("#of-from").fill("2026-11-15");
  await page.locator("#of-to").fill("2099-12-31");
  await page.locator("#of-percent").fill("1.5");
  await page.locator("#of-reason").fill(REASON);
  await page.getByTestId("of-submit").click();
  await expect(page.getByTestId("discount-detail-page")).toBeVisible();
  await expect(page.getByTestId("offer-products-all")).toBeVisible();
});

test("an edit never truncates: a title-only change on a quantity of 65 sticks (pack of 20) keeps 65, in base units, as version 2", async ({ page }) => {
  await superEnglish(page);
  await page.goto("/admin/discounts");
  await hydrated(page.getByTestId("offer-link-ODD-QTY")); // a click or fill before React attaches is lost or reverted (D-WEB-FIX-01)
  await page.getByTestId("offer-link-ODD-QTY").click();
  await expect(page.getByTestId("offer-value")).toContainText("3 packet(s) + 5 stick(s) (65 stick(s))");
  await page.getByTestId("offer-edit").click();
  await hydrated(page.getByTestId("offer-form"));
  await expect(page.locator("#of-threshold")).toHaveValue("65"); // opened in base units, not "3"
  await expect(page.locator("#of-reward-qty")).toHaveValue("10"); // not "0"
  await page.locator("#of-title-en").fill("Odd quantity offer, renamed");
  await page.locator("#of-reason").fill("Only the title changes here");
  await page.getByTestId("of-submit").click();
  await expect(page.getByTestId("offer-saved")).toBeVisible();
  await expect(page.getByTestId("offer-value")).toContainText("(65 stick(s))");
  await expect(page.getByTestId("offer-value")).toContainText("get 10 stick(s) free");
  await expect(page.getByTestId("offer-version")).toHaveCount(2);
  await page.getByTestId("offer-edit").click(); // saving again with no change is refused, nothing is sent
  await hydrated(page.getByTestId("offer-form"));
  await page.locator("#of-reason").fill("Nothing changes in this one");
  await page.getByTestId("of-submit").click();
  await expect(page.getByTestId("offer-form-banner")).toBeVisible();
});
