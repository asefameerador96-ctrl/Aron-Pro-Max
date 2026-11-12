// Loyalty programmes (portal-loyalty-web, D-LOY-WEB-01): gating by role and grant, list, detail (tiers, progress with guarded percent,
// enrolments, gifts, versions), create a draft with tiers, edit it (a new version), activate (Super Admin) and end it with a reason,
// enrol an outlet, the gift catalogue, plus the error, offline and long-Bangla states. Seed ids: 1 ASTHA-Q4 (active), 2 DL-OCT (active),
// 3 SS-NOV (draft), 4 ASTHA-Q3 (ended).
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
  await expect(page.locator('nav a[href="/admin/loyalty"]').first()).toBeVisible();
  await page.goto("/admin/loyalty");
  await expect(page.getByTestId("loyalty-page")).toBeVisible();
  await expect(page.getByTestId("programme-new")).toBeVisible();
});

test("gating: an ADMIN with loyalty at read sees the list without write controls; no grant hides the menu and refuses the URL", async ({ page, context }) => {
  await loginOk(page, "mloy1", "loy-pass-1", "123456");
  await page.goto("/admin/loyalty");
  await expect(page.getByTestId("loyalty-page")).toBeVisible();
  await expect(page.getByTestId("module-read-only")).toBeVisible();
  await expect(page.getByTestId("programme-new")).toHaveCount(0);
  await page.getByTestId("programme-link-SS-NOV").click();
  await expect(page.getByTestId("loyalty-detail-page")).toBeVisible();
  await expect(page.getByTestId("programme-edit")).toHaveCount(0);
  await expect(page.getByTestId("loyalty-status-panel")).toHaveCount(0);
  await expect(page.getByTestId("loyalty-enrol-form")).toHaveCount(0);
  await page.goto("/admin/loyalty/new");
  await expect(page.getByTestId("loyalty-form")).toHaveCount(0); // not granted for write: the not-granted page
  await page.goto("/admin/loyalty/gifts");
  await expect(page.getByTestId("gifts-super-only")).toBeVisible();
  await expect(page.getByTestId("gift-new")).toHaveCount(0);
  await context.clearCookies();
  await loginOk(page, "mgrant1", "grant-pass-1", "123456");
  await page.goto("/admin");
  await expect(page.locator('a[href="/admin/loyalty"]')).toHaveCount(0);
  await page.goto("/admin/loyalty");
  await expect(page.getByTestId("loyalty-page")).toHaveCount(0);
  await expect(page.getByTestId("programme-link-SS-NOV")).toHaveCount(0);
});

test("gating: a TSO and a discount-only ADMIN cannot open the section", async ({ page, context }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await page.goto("/admin/loyalty");
  await expect(page.getByTestId("loyalty-page")).toHaveCount(0);
  await context.clearCookies();
  await loginOk(page, "mdisc1", "disc-pass-1", "123456");
  await page.goto("/admin/loyalty");
  await expect(page.getByTestId("loyalty-page")).toHaveCount(0);
});

test("list: the seeded programmes with kind and status, filters by status, kind and text", async ({ page }) => {
  await superEnglish(page);
  await page.goto("/admin/loyalty");
  const rows = page.getByTestId("data-table").locator("tbody tr");
  await expect(rows).toHaveCount(4);
  await expect(page.getByTestId("programme-status-SS-NOV")).toContainText("Draft");
  await expect(page.getByTestId("programme-status-ASTHA-Q4")).toContainText("Active");
  await expect(page.getByTestId("programme-status-ASTHA-Q3")).toContainText("Ended");
  await expect(rows.filter({ hasText: "DL-OCT" })).toContainText("Diamond League");
  await page.goto("/admin/loyalty?status=active");
  await expect(rows).toHaveCount(2);
  await page.goto("/admin/loyalty?kind=astha");
  await expect(rows).toHaveCount(2);
  await page.goto("/admin/loyalty?q=superstar");
  await expect(rows).toHaveCount(1);
  await page.goto("/admin/loyalty?q=nothing-like-this");
  await expect(page.getByTestId("table-empty")).toBeVisible();
});

test("detail: tiers with STD thresholds, channels, progress with a guarded percent, enrolments, gifts, versions and the audit trail", async ({ page }) => {
  await superEnglish(page);
  await page.goto("/admin/loyalty");
  await hydrated(page.getByTestId("programme-link-ASTHA-Q4"));
  await page.getByTestId("programme-link-ASTHA-Q4").click();
  await expect(page.getByTestId("tier-platinum")).toContainText("5,000 STD");
  await expect(page.getByTestId("tier-gold")).toContainText("3,000.5 STD");
  await expect(page.getByTestId("tier-silver")).toContainText("Not set"); // nothing invented
  await expect(page.getByTestId("programme-channels")).toContainText("Astha outlets");
  await expect(page.getByTestId("progress-pct-platinum")).toHaveText("50.00%");
  await expect(page.getByTestId("progress-pct-gold")).toHaveText("50.00%");
  await expect(page.getByTestId("progress-pct-silver")).toHaveText("—"); // zero target: no percent, no infinity
  await expect(page.getByTestId("progress-platinum")).toContainText("3,000 STD");
  await expect(page.getByTestId("points-empty")).toBeVisible();
  await expect(page.getByTestId("programme-enrolments")).toContainText("#101");
  await expect(page.getByTestId("programme-gifts")).toContainText("Rice cooker");
  await expect(page.getByTestId("programme-version")).toHaveCount(1);
  await expect(page.getByTestId("audit-history")).toBeVisible();
  await expect(page.getByTestId("loyalty-status-panel")).toBeVisible(); // active: End programme is offered
  await page.goto("/admin/loyalty/2");
  await expect(page.getByTestId("points")).toContainText("1,200 points");
  await expect(page.getByTestId("points")).toContainText("Tk 800.000"); // 400 points x 2,000 milli-taka
  await page.goto("/admin/loyalty/9999");
  await expect(page.getByTestId("loyalty-missing")).toBeVisible();
});

test("create a draft with tiers, edit it into version 2, activate it as Super Admin with a reason, then end it", async ({ page }) => {
  await superEnglish(page);
  await page.goto("/admin/loyalty/new");
  const form = page.getByTestId("loyalty-form");
  await hydrated(form);
  await hydrated(page.locator("#lf-code"));
  await page.getByTestId("lf-submit").click();
  await expect(page.getByTestId("error-lf-code")).toBeVisible(); // required fields are named, nothing is sent
  await page.locator("#lf-code").fill("NEW-E2E");
  await page.locator("#lf-name-en").fill("E2E Astha");
  await page.locator("#lf-name-bn").fill("ই-টু-ই আস্থা");
  await page.locator("#lf-period").fill("2027-Q1");
  await page.locator("#lf-from").fill("2027-01-01");
  await page.locator("#lf-to").fill("2099-12-31");
  await page.getByTestId("lf-add-tier").click();
  await page.locator("#lf-tier-0-code").fill("gold");
  await page.locator("#lf-tier-0-en").fill("Gold");
  await page.locator("#lf-tier-0-threshold").fill("3000.5");
  await page.getByTestId("lf-add-tier").click();
  await page.locator("#lf-tier-1-code").fill("platinum");
  await page.locator("#lf-tier-1-en").fill("Platinum");
  await page.locator("#lf-tier-1-threshold").fill("3000.5"); // not rising
  await page.getByTestId("lf-channel-Astha").check();
  await page.locator("#lf-reason").fill(REASON);
  await page.getByTestId("lf-submit").click();
  await expect(page.getByTestId("error-lf-tier-1-threshold")).toBeVisible();
  await page.locator("#lf-tier-1-threshold").fill("5000");
  await page.getByTestId("lf-submit").click();
  await expect(page.getByTestId("loyalty-detail-page")).toBeVisible();
  await expect(page.getByTestId("programme-saved")).toBeVisible();
  await expect(page.getByTestId("programme-status")).toContainText("Draft");
  await expect(page.getByTestId("tier-gold")).toContainText("3,000.5 STD");
  await expect(page.getByTestId("tier-platinum")).toContainText("5,000 STD");

  await page.getByTestId("programme-edit").click();
  await hydrated(page.getByTestId("loyalty-form"));
  await expect(page.locator("#lf-tier-0-threshold")).toHaveValue("3000.5");
  await expect(page.locator("#lf-code")).toBeDisabled();
  await page.locator("#lf-tier-1-threshold").fill("6000");
  await page.locator("#lf-reason").fill("Raise the platinum threshold");
  await page.getByTestId("lf-submit").click();
  await expect(page.getByTestId("tier-platinum")).toContainText("6,000 STD");
  await expect(page.getByTestId("programme-version")).toHaveCount(2);

  await hydrated(page.getByTestId("loyalty-status-panel"));
  await page.getByTestId("ls-active").click();
  await page.getByTestId("ls-confirm").click();
  await expect(page.getByTestId("error-ls-reason")).toBeVisible(); // the reason is mandatory
  await page.locator("#ls-reason").fill("Go live for the first quarter");
  await page.getByTestId("ls-confirm").click();
  await expect(page.getByTestId("programme-status")).toContainText("Active");
  await expect(page.getByTestId("programme-edit")).toHaveCount(0);
  await page.getByTestId("ls-inactive").click();
  await page.locator("#ls-reason").fill("Programme ended by the owner");
  await page.getByTestId("ls-confirm").click();
  await expect(page.getByTestId("programme-status")).toContainText("Ended");
  await expect(page.getByTestId("audit-entry").first()).toBeVisible();
  await expect(page.getByTestId("enrol-ended")).toBeVisible();
});

test("enrol an outlet: a negative target is refused, a valid row appears in the list with its tier", async ({ page }) => {
  await superEnglish(page);
  await page.goto("/admin/loyalty/1");
  await hydrated(page.getByTestId("loyalty-enrol-form"));
  await hydrated(page.locator("#le-outlet"));
  await page.locator("#le-outlet").fill("301");
  await page.locator("#le-tier").selectOption("gold");
  await page.locator("#le-target").fill("-37500");
  await page.locator("#le-from").fill("2027-01-01");
  await page.locator("#le-reason").fill("Owner enrolled the outlet");
  await page.getByTestId("le-submit").click();
  await expect(page.getByTestId("error-le-target")).toBeVisible();
  await page.locator("#le-target").fill("1200.25");
  await page.getByTestId("le-submit").click();
  await expect(page.getByTestId("loyalty-enrol-banner")).toContainText("enrolled");
  await expect(page.getByTestId("programme-enrolments")).toContainText("#301");
  await expect(page.getByTestId("programme-enrolments")).toContainText("1,200.25 STD");
});

test("an ADMIN with write cannot activate a draft (Super Admin only) but the draft can still be edited", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/loyalty");
  await hydrated(page.getByTestId("programme-link-SS-NOV"));
  await page.getByTestId("programme-link-SS-NOV").click();
  await expect(page.getByTestId("ls-super-only")).toBeVisible();
  await expect(page.getByTestId("ls-active")).toHaveCount(0);
  await expect(page.getByTestId("programme-edit")).toBeVisible();
  await page.goto("/admin/loyalty/gifts");
  await expect(page.getByTestId("gifts-super-only")).toBeVisible(); // the catalogue is Super Admin only
  await expect(page.getByTestId("gift-new")).toHaveCount(0);
});

test("gift catalogue: the Super Admin adds a gift with a points cost and edits it; Astha gifts carry no points", async ({ page }) => {
  await superEnglish(page);
  await page.goto("/admin/loyalty/gifts");
  const rows = page.getByTestId("data-table").locator("tbody tr");
  await expect(rows).toHaveCount(4);
  await expect(rows.filter({ hasText: "rice_cooker" })).toContainText("Chosen, not redeemed");
  await expect(rows.filter({ hasText: "tornado_fan" })).toContainText("400 points");
  await hydrated(page.getByTestId("gift-new"));
  await page.getByTestId("gift-new").click();
  await hydrated(page.getByTestId("loyalty-gift-form"));
  await page.getByTestId("lg-submit").click();
  await expect(page.getByTestId("error-lg-programme")).toBeVisible();
  await page.locator("#lg-programme").selectOption({ label: "DL-OCT · Diamond League October" });
  await page.locator("#lg-code").fill("water_filter");
  await page.locator("#lg-name-en").fill("Water filter");
  await page.locator("#lg-points").fill("2.5");
  await page.locator("#lg-reason").fill("Owner added a gift to the catalogue");
  await page.getByTestId("lg-submit").click();
  await expect(page.getByTestId("error-lg-points")).toBeVisible(); // a whole number only
  await page.locator("#lg-points").fill("300");
  await page.getByTestId("lg-submit").click();
  await expect(page.getByTestId("gift-saved")).toBeVisible();
  await expect(rows).toHaveCount(5);
  await expect(rows.filter({ hasText: "water_filter" })).toContainText("300 points");
  await hydrated(page.getByTestId("gift-edit-water_filter"));
  await page.getByTestId("gift-edit-water_filter").click();
  await hydrated(page.getByTestId("loyalty-gift-form"));
  await expect(page.locator("#lg-code")).toBeDisabled();
  await page.locator("#lg-points").fill("350");
  await page.locator("#lg-reason").fill("The price in points went up");
  await page.getByTestId("lg-submit").click();
  await expect(rows.filter({ hasText: "water_filter" })).toContainText("350 points");
});

test("states: the server failing shows the error banner with a reload link", async ({ page }) => {
  await superLogin(page);
  await fetch(`${MOCK}/__mock/fail`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ prefix: "/v1/admin/programmes" }) }).catch(() => undefined);
  await page.goto("/admin/loyalty");
  await expect(page.getByTestId("loyalty-error")).toBeVisible();
  await expect(page.getByTestId("loyalty-error")).toContainText(/Reload|রিলোড/);
});

for (const [name, size] of [["phone", { width: 360, height: 740 }], ["laptop", { width: 1366, height: 768 }]] as const) {
  test(`long Bangla: the list, detail, form, gifts and status panel stay inside the page (${name})`, async ({ page }) => {
    await superLogin(page); // Bangla is the user's own language; the sign-in header shows "who" only on a wide window, so resize after it
    await page.setViewportSize(size);
    for (const path of ["/admin/loyalty", "/admin/loyalty/1", "/admin/loyalty/3", "/admin/loyalty/3/edit", "/admin/loyalty/new", "/admin/loyalty/gifts?new=1"]) {
      await page.goto(path);
      await expect(page.locator("h1").first()).toBeVisible();
      await page.waitForLoadState("networkidle");
      const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(over, `${path} overflows by ${over}px`).toBeLessThanOrEqual(1);
    }
    await page.goto("/admin/loyalty/3"); // the open status panel (reason field and confirm step) in Bangla
    await hydrated(page.getByTestId("loyalty-status-panel"));
    await page.getByTestId("ls-active").click();
    await expect(page.getByTestId("ls-form")).toBeVisible();
    const overPanel = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overPanel, `open status panel overflows by ${overPanel}px`).toBeLessThanOrEqual(1);
  });
}
