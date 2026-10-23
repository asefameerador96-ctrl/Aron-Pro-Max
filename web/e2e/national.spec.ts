// The national dashboard (docs/34 s2, D-DASH-02): filter panel, the twelve cards, "i" pop-ups, gear, FF Geo, Route map and Sale Position
// pages, role views. Figures at "everything selected" are the owner's screenshot of 2026-10-22 (docs/ui-reference/web/dashboard.md).
// Screenshots go to the test report (and to the web-snapshots workflow); no golden is recorded locally.
import { expect, test, type Page, type TestInfo } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

async function open(page: Page, path = "/", user: [string, string] = ["analyst1", "analyst-pass-1"]) {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await loginOk(page, user[0], user[1]);
  await page.getByRole("link", { name: "English" }).click();
  await page.goto(path);
  await expect(page.getByTestId("national-dashboard")).toBeVisible();
}
async function shot(page: Page, info: TestInfo, name: string) {
  await page.waitForLoadState("networkidle");
  const path = info.outputPath(`${name}.png`);
  await page.screenshot({ path, fullPage: true });
  await info.attach(name, { path, contentType: "image/png" });
}

test("the national dashboard shows the twelve cards with the screenshot's figures", async ({ page }, info) => {
  await page.setViewportSize({ width: 1600, height: 1000 });
  await open(page);
  await expect(page.getByTestId("sales-total-cigarette")).toHaveText("3.64 Cr");
  await expect(page.getByTestId("sales-total-bidi")).toHaveText("34.88 Lakh");
  await expect(page.getByTestId("sales-total-lighter")).toHaveText("1.06 Lakh");
  await expect(page.getByTestId("sales-total-match")).toHaveText("11,165");
  await expect(page.getByTestId("sales-ring-cigarette")).toHaveAttribute("aria-label", /48\.5%/);
  await expect(page.getByTestId("sales-ring-match")).toHaveAttribute("aria-label", /17\.3%/);
  await expect(page.getByTestId("cpr-target")).toHaveText("4.60 Lakh");
  await expect(page.getByTestId("cpr-calls")).toHaveText("1.74 Lakh");
  await expect(page.getByTestId("cpr-ring")).toHaveAttribute("aria-label", /37\.8%|37\.9%/);
  await expect(page.getByTestId("ch-calls-GT")).toContainText("1.36 Lakh (78.");
  await expect(page.getByTestId("ch-std-GT")).toContainText("2.64 Cr (72.");
  await expect(page.getByTestId("final-total")).toHaveText("1,051");
  await expect(page.getByTestId("final-service")).toHaveText("1,046");
  await expect(page.getByTestId("final-ring")).toHaveAttribute("aria-label", /0\.0%/);
  await expect(page.getByTestId("login-status-pct")).toHaveText("99.8%");
  await expect(page.getByTestId("submit-status-pct")).toHaveText("0.1%");
  await expect(page.getByTestId("geo-ring")).toHaveAttribute("aria-label", /97\.96%/);
  await expect(page.getByTestId("breakdown-title")).toContainText("Breakdown");
  await expect(page.getByTestId("nat-last-updated")).toContainText("Last updated");
  await shot(page, info, "national-dashboard-1600");
});

test("the 'i' button shows Daily Target, Sales so far and Remaining", async ({ page }) => {
  await open(page);
  await page.getByTestId("sales-info-cigarette").click();
  const dlg = page.getByTestId("sales-info-cigarette-dialog");
  await expect(dlg).toBeVisible();
  await expect(dlg).toContainText("Daily Target");
  await expect(dlg).toContainText("Sales so far");
  await expect(dlg).toContainText("Remaining");
  await page.keyboard.press("Escape");
  await expect(dlg).toBeHidden();
});

test("filters change the cards only when Apply Filter is pressed, and the House level exists", async ({ page }) => {
  await open(page);
  await expect(page.getByTestId("filter-house")).toBeVisible();
  const before = await page.getByTestId("sales-total-cigarette").innerText();
  await page.getByTestId("filter-wing").getByRole("button", { name: /All Selected/ }).click();
  await page.getByRole("option", { name: "Dhaka", exact: true }).locator("input").check();
  await expect(page.getByTestId("sales-total-cigarette")).toHaveText(before); // nothing refreshed yet
  await page.getByTestId("filter-apply").click();
  await expect(page).toHaveURL(/wing=1/);
  await expect(page.getByTestId("sales-total-cigarette")).not.toHaveText(before);
});

test("the gear switches the breakdown to Memo by Brand and the choice shows in the title", async ({ page }) => {
  await open(page);
  await page.getByTestId("breakdown-gear").locator("summary").click();
  await page.getByTestId("breakdown-memo-segment").click();
  await expect(page).toHaveURL(/bm=memo/);
  await expect(page.getByTestId("breakdown-title")).toContainText("Memo");
});

test("FF Geo lists the routes with colours when no Maps key is set, and Filter by Color narrows them", async ({ page }) => {
  await open(page);
  await expect(page.getByTestId("ff-placeholder")).toBeVisible();
  const all = await page.getByTestId("ff-list").locator("li").count();
  expect(all).toBeGreaterThan(5);
  await page.getByTestId("ff-colors").getByRole("button", { name: /All Colors/ }).click();
  await page.getByRole("option", { name: /Red/ }).locator("input").check();
  const red = page.getByTestId("ff-list").locator("li");
  expect(await red.count()).toBeLessThan(all);
  for (const li of await red.all()) await expect(li).toHaveAttribute("data-color", "red");
});

test("a route opens its Map Details page, an outlet opens its Sale Position page with the force-sale photo", async ({ page }, info) => {
  await page.setViewportSize({ width: 1600, height: 1000 });
  await open(page);
  await page.getByTestId("ff-list").getByRole("link", { name: "View Live Map" }).first().click();
  await expect(page.getByTestId("route-map-page")).toBeVisible();
  await expect(page.getByTestId("route-map-header")).toContainText("View Live Map Of SR Position");
  await expect(page.getByTestId("route-cpr")).toContainText("50");
  await expect(page.getByTestId("tva-cigarette")).toContainText("26%");
  await expect(page.getByTestId("time-nat.rm.distance")).toHaveText("1.2 km");
  await shot(page, info, "route-map-1600");
  await page.getByTestId("route-map-list").getByRole("link", { name: "Outlet details" }).first().click();
  await expect(page.getByTestId("sale-position-page")).toBeVisible();
  await expect(page.getByTestId("sale-position-header")).toContainText("SR Hit / Miss Position (81.83 m)");
  await page.getByTestId("sale-position-list").getByRole("button", { name: "View Image" }).first().click();
  await expect(page.getByTestId("photo-viewer")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByTestId("photo-viewer")).toBeHidden();
  await shot(page, info, "sale-position-1600");
});

test("a brand user sees the product cards only", async ({ page }, info) => {
  await page.setViewportSize({ width: 1600, height: 1000 });
  await open(page, "/", ["brand1", "brand-pass-1"]);
  await expect(page.getByTestId("nat-product-only")).toBeVisible();
  for (const id of ["nat-sales-cigarette", "nat-ch-std", "nat-breakdown"]) await expect(page.getByTestId(id)).toBeVisible();
  for (const id of ["nat-cpr", "nat-ch-calls", "nat-final", "nat-login", "nat-geo", "nat-ffgeo"]) await expect(page.getByTestId(id)).toHaveCount(0);
  await shot(page, info, "national-dashboard-brand");
});

test("Bangla mode uses Bengali digits and words on the cards", async ({ page }, info) => {
  await page.setViewportSize({ width: 1600, height: 1000 });
  await loginOk(page, "analyst1", "analyst-pass-1");
  await page.getByRole("link", { name: "বাংলা" }).click();
  await page.goto("/");
  await expect(page.getByTestId("sales-total-cigarette")).toHaveText("৩.৬৪ কোটি");
  await expect(page.getByTestId("sales-total-bidi")).toHaveText("৩৪.৮৮ লাখ");
  await shot(page, info, "national-dashboard-bn");
});

test("the dashboard, the route map and the sale position pass axe A/AA", async ({ page }) => {
  const { default: AxeBuilder } = await import("@axe-core/playwright");
  await page.setViewportSize({ width: 1366, height: 900 });
  await open(page);
  const check = async (name: string) => {
    const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze();
    expect(r.violations.map((v) => `${name}: ${v.id} ${v.nodes[0]?.target}`)).toEqual([]);
  };
  await check("dashboard");
  await page.goto("/dashboard/route-map/100");
  await expect(page.getByTestId("route-map-page")).toBeVisible();
  await check("route-map");
  await page.goto("/dashboard/sale-position/100000");
  await expect(page.getByTestId("sale-position-page")).toBeVisible();
  await check("sale-position");
});

test("an admin without the dashboard grant gets a clear no-access note, not broken cards", async ({ page }) => {
  await open(page, "/", ["nodash1", "nodash-pass-1"]);
  await expect(page.getByTestId("nat-no-access")).toBeVisible();
  await expect(page.getByTestId("nat-grid")).toHaveCount(0);
  await expect(page.getByTestId("nat-filters-error")).toHaveCount(0);
});
