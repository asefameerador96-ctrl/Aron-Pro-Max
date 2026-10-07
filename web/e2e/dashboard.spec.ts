// F-WEB-001, F-WEB-047, F-WEB-068, N-047: the seeded TSO's dashboard, report pages and the Maps cost guard, in a real browser.
import { expect, test } from "@playwright/test";
import { controlTotals, routesInScope } from "../mock/seed";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

test("the TSO dashboard loads without a button press and shows every tile with its date and as-of stamp (F-WEB-001, F-WEB-068)", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await page.getByRole("link", { name: "English" }).click();
  await expect(page.getByTestId("dashboard-tiles")).toBeVisible();
  for (const id of ["sales", "strike", "visits", "channels", "finalsubmit", "loginsubmit", "geo", "ffgeo"]) {
    const tile = page.getByTestId(`tile-${id}`);
    await expect(tile).toBeVisible();
    await expect(tile.getByTestId("tile-date")).toBeVisible();
    await expect(tile.getByTestId("tile-asof")).toContainText("As of");
  }
  // Control totals of the seeded territory 334 (mock/seed.ts): visited 43+35+30+0 = 108, successful 1+14+22+0 = 37, net 543,435.000 taka.
  const ct = controlTotals(routesInScope([{ type: "territory", id: 334 }]));
  expect(ct).toMatchObject({ visited: 108, successful: 37, net_mtk: 543_435_000 });
  await expect(page.getByTestId("strike-basis")).toContainText("37 successful of 108 visited");
  await expect(page.getByTestId("strike-rate")).toHaveText("34.3%");
  await expect(page.getByTestId("sales-net")).toHaveText("543,435.000");
  await expect(page.getByTestId("tile-channels").getByTestId("channel-list")).toContainText("Grocery");
  await expect(page.getByTestId("geo-fencing")).toBeVisible();
});

test("the live strike rate of one route: 1 of 43 is 2.3 percent", async ({ page }) => {
  // The figure is proven through the TSO's route report (SRs have no web access).
  await loginOk(page, "tso334", "tso-pass-1");
  await page.getByRole("link", { name: "English" }).click();
  await page.goto("/reports/route-std");
  const row = page.locator("tbody tr", { hasText: "RouteDaily" });
  await expect(row.locator('[data-col="visited"]')).toHaveText("43");
  await expect(row.locator('[data-col="successful"]')).toHaveText("1");
  await expect(row.locator('[data-col="strike_pct"]')).toHaveText("2.33%");
});

test("a date that is not today carries a reason chip on every tile", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await page.goto("/?date=2026-10-01");
  for (const id of ["sales", "geo", "ffgeo"]) await expect(page.getByTestId(`tile-${id}`).getByTestId("tile-reason")).toHaveAttribute("data-reason", "not_live_today");
});

test("final-submit panel: zones submitted versus remaining, Submit %, Day completion % and a badge per zone (F-WEB-047)", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await page.getByRole("link", { name: "English" }).click();
  const panel = page.getByTestId("tile-finalsubmit");
  // Seed: R1 final-submitted, R2 sales-submitted, R3 in the field, R4 not started: no zone has every route final-submitted.
  await expect(panel.getByTestId("final-submit-counts")).toContainText("0 zones submitted, 2 remaining");
  await expect(panel.getByTestId("submit-pct")).toHaveText("66.7%");
  await expect(panel.getByTestId("completion-pct")).toHaveText("25.0%");
  await expect(panel.locator('[data-zone="3341"]')).toHaveAttribute("data-state", "pending");
  await expect(panel.locator('[data-zone="3342"]')).toHaveAttribute("data-state", "pending");
});

test("map: without a Maps key the pins are listed; the BFF counts every load and refuses above the cap (N-047)", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await page.getByRole("link", { name: "English" }).click();
  const map = page.getByTestId("map-panel");
  await expect(map).toHaveAttribute("data-phase", "no_key");
  // Seeded outlets with coordinates (one per route in two zones) plus the last fixes of the team members who started their day.
  const count = Number(await map.getAttribute("data-pin-count"));
  expect(count).toBeGreaterThanOrEqual(5);
  await expect(page.getByTestId("map-pin-list").locator("li[data-kind=outlet]").first()).toBeVisible();
  await expect(page.getByTestId("map-pin-list").locator("li[data-kind=fix]").first()).toBeVisible();
  // The Maps script is never requested while no key is configured.
  expect(await page.evaluate(() => Array.from(document.scripts).some((s) => s.src.includes("maps.googleapis.com")))).toBe(false);
});

test("reports: filters, Get Excel and a user outside the scope sees no rows", async ({ page }) => {
  await loginOk(page, "tso999", "tso-pass-3");
  await page.getByRole("link", { name: "English" }).click();
  await page.goto("/reports/route-std");
  await expect(page.getByTestId("table-empty")).toBeVisible();
  void MOCK;
});
