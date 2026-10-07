// Dashboard pages in a real browser against the seeded mock: login (F-WEB-043), credentials (F-WEB-033), tracking (F-WEB-038/039/028),
// sync health (F-WEB-045), exceptions (F-WEB-057), leave (F-WEB-046), tutorial (F-WEB-035), routes (F-WEB-010), products, reports.
import { expect, test, type Page } from "@playwright/test";
import { MOCK, login, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});
const english = async (page: Page) => page.getByRole("link", { name: "English" }).click();

test("login: User ID is case-insensitive, show/hide works, Remember me is off, there is no forgot-password link", async ({ page }) => {
  await page.goto("/login");
  await english(page);
  const form = page.getByTestId("login-form");
  await expect(form.getByText("User ID")).toBeVisible();
  await expect(page.getByTestId("remember-me")).not.toBeChecked();
  await expect(page.getByText(/forgot/i)).toHaveCount(0);
  await expect(page.locator('a[href*="forgot"], a[href*="reset"]')).toHaveCount(0);
  const pw = form.locator('input[name="password"]');
  await pw.fill("tso-pass-1");
  await expect(pw).toHaveAttribute("type", "password");
  await page.getByTestId("toggle-password").click();
  await expect(pw).toHaveAttribute("type", "text");
  await page.getByTestId("toggle-password").click();
  await expect(pw).toHaveAttribute("type", "password");
  await form.locator('input[name="username"]').fill("TSO334");
  await form.locator('button[type="submit"]').click();
  await expect(page.getByTestId("who")).toBeVisible();
});

test("login: Remember me off gives session cookies, on gives persistent ones", async ({ page, context }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  let jar = await context.cookies();
  expect(jar.find((c) => c.name === "aron_sess")?.expires).toBe(-1);
  await context.clearCookies();
  await page.goto("/login");
  const form = page.getByTestId("login-form");
  await form.locator('input[name="username"]').fill("tso334");
  await form.locator('input[name="password"]').fill("tso-pass-1");
  await page.getByTestId("remember-me").check();
  await form.locator('button[type="submit"]').click();
  await expect(page.getByTestId("who")).toBeVisible();
  jar = await context.cookies();
  expect(jar.find((c) => c.name === "aron_sess")?.expires).toBeGreaterThan(Date.now() / 1000);
});

test("credentials: the guideline, authored field errors and a successful change", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await page.goto("/credentials");
  await expect(page.getByTestId("password-guideline")).toContainText("At least 12 characters");
  const f = page.getByTestId("password-form");
  await f.locator("button[type=submit]").click();
  await expect(page.getByTestId("pw-error-old")).toHaveText("Fill this in.");
  await expect(page.getByTestId("pw-error-next")).toHaveText("Fill this in.");
  await page.getByTestId("pw-old").fill("tso-pass-1");
  await page.getByTestId("pw-next").fill("shortone");
  await page.getByTestId("pw-confirm").fill("different");
  await f.locator("button[type=submit]").click();
  await expect(page.getByTestId("pw-error-next")).toHaveText("Use at least 12 characters.");
  await expect(page.getByTestId("pw-error-confirm")).toHaveText("The two new passwords are not the same.");
  await page.getByTestId("pw-next").fill("Correct-Horse-9");
  await page.getByTestId("pw-confirm").fill("Correct-Horse-9");
  await page.getByTestId("pw-old").fill("wrong-old-pw");
  await f.locator("button[type=submit]").click();
  await expect(page.getByTestId("pw-error-old")).toHaveText("The old password is not right.");
  await page.getByTestId("pw-old").fill("tso-pass-1");
  await f.locator("button[type=submit]").click();
  await expect(page.getByTestId("pw-done")).toBeVisible();
  // Bangla errors too.
  await page.getByRole("link", { name: "বাংলা" }).click();
  await page.getByTestId("pw-old").fill("x");
  await page.getByTestId("pw-next").fill("a");
  await f.locator("button[type=submit]").click();
  await expect(page.getByTestId("pw-error-next")).toHaveText("কমপক্ষে ১২টি অক্ষর দিন।");
});

test("daily tracking: buckets with the exception bucket distinct, yesterday comparator, and take-action stores a note after 17:00", async ({ page }) => {
  await loginOk(page, "tso335", "tso-pass-2");
  await english(page);
  await page.goto("/daily-tracking?date=2026-10-06"); // an earlier business date: take action is open
  await expect(page.getByTestId("bucket-count-exception")).toHaveText("1");
  await expect(page.getByTestId("bucket-count-not_logged_in")).toHaveText("0");
  await expect(page.getByTestId("bucket-compare-exception")).toContainText("Same time yesterday");
  await expect(page.getByTestId("comparator-note")).toHaveCount(0); // the server sent the same-time comparator
  await expect(page.getByTestId("take-action-state")).toContainText("Take action is open");
  const row = page.locator('tr[data-route="10352"]');
  await expect(row).toHaveAttribute("data-bucket", "exception");
  await row.getByText("Take action").click();
  await row.locator("textarea").fill("Rain: please plan a make-up visit");
  await row.getByRole("button", { name: "Send" }).click();
  await expect(row.getByTestId("action-sent")).toContainText("Note saved");
  const state = (await (await fetch(`${MOCK}/__mock/state`)).json()) as { actions: { note: string; route_id: number }[] };
  expect(state.actions).toEqual([expect.objectContaining({ route_id: 10352, note: "Rain: please plan a make-up visit" })]);
});

test("daily tracking: take action is not offered for a future date", async ({ page }) => {
  await loginOk(page, "tso335", "tso-pass-2");
  await english(page);
  await page.goto("/daily-tracking?date=2999-01-01");
  await expect(page.getByTestId("take-action-state")).toContainText("opens at 17:00");
  await expect(page.getByText("Take action", { exact: true })).toHaveCount(0);
});

test("TSO daily tracking buckets routes at 100, 90 to 100, 80 to 90 and below 80 percent", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await page.goto("/tso-daily-tracking");
  for (const b of ["ge_100", "from_90", "from_80", "below_80"]) await expect(page.getByTestId(`bucket-${b}`)).toBeVisible();
  await expect(page.getByTestId("bucket-ge_100")).toContainText("RouteDaily"); // 43 of 43
  await expect(page.getByTestId("bucket-from_80")).toContainText("Banani North"); // 35 of 40 = 87.5
  await expect(page.getByTestId("bucket-below_80")).toContainText("Banani South"); // 30 of 38 = 78.9
  await expect(page.getByTestId("bucket-not_logged_in")).toContainText("Banani AMO");
});

test("sync health: seven figures and a drill from zone to route to device", async ({ page }) => {
  await loginOk(page, "analyst1", "analyst-pass-1");
  await english(page);
  await page.goto("/sync-health");
  await expect(page.getByTestId("sync-login")).toContainText("%");
  await expect(page.getByTestId("sync-final")).toContainText("of 3 zones");
  await expect(page.getByTestId("sync-trickle")).toContainText("worst");
  await expect(page.getByTestId("sync-quarantine")).toHaveText("1");
  await expect(page.getByTestId("sync-ack")).toContainText("87.5%");
  await expect(page.getByTestId("sync-photos")).toContainText("4");
  await expect(page.getByTestId("photos-oldest")).toContainText("90 min");
  await page.getByTestId("zone-table").getByRole("link").first().click();
  await expect(page.getByTestId("route-table")).toBeVisible();
  await page.getByTestId("route-table").getByRole("link", { name: "Banani North" }).click();
  await expect(page.getByTestId("device-table")).toBeVisible();
  await expect(page.getByTestId("device-table").locator("tbody tr")).toHaveCount(1);
});

test("sync health: a server that leaves the v1.2 figures out gets 'not available' (no admin read), not a guess", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await page.goto("/sync-health");
  await expect(page.getByTestId("sync-ack").getByTestId("not-available")).toBeVisible();
  await expect(page.getByTestId("sync-photos").getByTestId("not-available")).toBeVisible();
});

test("exceptions: the re-sampled signal is marked and a confirm closes it", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await page.goto("/exceptions");
  await expect(page.getByTestId("resampled-badge")).toBeVisible();
  await page.getByTestId("review-confirmed-900").click();
  await expect(page.getByTestId("table-empty")).toBeVisible();
  await page.goto("/exceptions?status=confirmed");
  await expect(page.locator('tr[data-signal="900"]')).toHaveAttribute("data-status", "confirmed");
});

test("leave: a DMO approves and the TSO sees the new status; a TSO has no buttons", async ({ page, context }) => {
  await loginOk(page, "dmo1", "dmo-pass-1");
  await english(page);
  await page.goto("/leave");
  await expect(page.locator("tbody tr")).toHaveCount(2);
  await page.getByTestId("approve-11111111-1111-4111-8111-111111111111").click();
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await context.clearCookies();
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await page.goto("/leave?status=approved");
  await expect(page.locator('tr[data-status="approved"]')).toHaveCount(1);
  await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
});

test("tutorial: four manuals, and a video added in the portal appears", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await page.goto("/tutorial");
  await expect(page.getByTestId("tutorial-manual").locator("li")).toHaveCount(4);
  await fetch(`${MOCK}/__mock/tutorial`, { method: "POST", body: JSON.stringify({ tutorial_id: 9, kind: "video", title_en: "How to final submit", title_bn: "ফাইনাল সাবমিট", url: "https://files.example/v.mp4", sort: 9 }) });
  await page.reload();
  await expect(page.getByTestId("tutorial-video")).toContainText("How to final submit");
});

test("routes: an AMO route shows SR Not Set", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await page.goto("/routes");
  await expect(page.locator('tr[data-route="10234"] [data-col="sr"]')).toHaveText("Not Set");
  await expect(page.locator('tr[data-route="10233"] [data-col="sr"]')).toHaveText("Jamal Uddin");
});

test("products: brand lists 17 brands read-only and the tree expands to SKU", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await page.goto("/products/brand");
  await expect(page.getByTestId("products-table").locator("tbody tr")).toHaveCount(17);
  await expect(page.getByRole("button", { name: /save|edit|delete/i })).toHaveCount(0);
  await page.goto("/products/tree");
  await expect(page.getByTestId("tree-count")).toContainText("3 SKUs");
  const sku = page.locator('[data-level="sku"]', { hasText: "Sample King 20s" });
  await expect(sku).toBeHidden();
  for (const name of ["Cigarette", "Premium", "Sample", "King Size"]) await page.locator("summary", { hasText: name }).first().click();
  await expect(sku).toBeVisible();
});

test("reports: filters, Get Excel link, PII masked for a TSO and shown to an analyst, menu lists the reports", async ({ page, context }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await expect(page.getByRole("link", { name: "Browse Retailer" })).toBeVisible();
  await page.goto("/reports/retailers");
  await expect(page.locator('td[data-col="phone"]').first()).toHaveText("••••");
  await expect(page.locator('td[data-col="phone"]').first()).toHaveAttribute("data-pii", "masked");
  await expect(page.getByTestId("get-excel")).toHaveAttribute("href", /\/api\/bff\/reports\/retailers\/export\?.*format=xlsx/);
  // The scope filter shows territory 334 as fixed and offers its zones only.
  await expect(page.getByTestId("scope-locked-territory")).toContainText("Banani");
  await expect(page.getByTestId("scope-select-zone").locator("option")).toHaveCount(3); // All + two zones
  await context.clearCookies();
  await loginOk(page, "analyst1", "analyst-pass-1");
  await english(page);
  await page.goto("/reports/retailers");
  await expect(page.locator('td[data-col="phone"]').first()).not.toHaveText("••••");
  await expect(page.getByTestId("scope-select-wing")).toBeVisible();
});

test("reports: the Excel download is the screen's filter and is logged by the server", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await page.goto("/reports/route-std?from=2026-10-01&to=2026-10-05&zone=3341");
  const href = await page.getByTestId("get-excel").getAttribute("href");
  const res = await page.request.get(href!);
  expect(res.status()).toBe(200);
  expect(await res.text()).toContain("WATERMARK");
  const state = (await (await fetch(`${MOCK}/__mock/state`)).json()) as { exports: { query: { period: unknown; geo: unknown } }[] };
  expect(state.exports[0]!.query.period).toEqual({ from: "2026-10-01", to: "2026-10-05" });
  expect(state.exports[0]!.query.geo).toEqual({ zone: [3341] });
});

test("an SR cannot reach the dashboards and login says so", async ({ page }) => {
  await login(page, "sr334001", "sr-pass-1");
  await expect(page.getByTestId("login-blocked")).toBeVisible();
});
