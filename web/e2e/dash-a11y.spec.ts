// docs/31 quality bar for the dashboards: an axe pass (WCAG 2.x A/AA, includes colour contrast) on every dashboard route and every
// drill-down, in light, dark and sunlight; a keyboard-only path through the Filter and the tables; Bangla digits on the home.
import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

export const DASH_PAGES = ["/", "/daily-tracking", "/tso-daily-tracking", "/final-submit", "/sync-health", "/exceptions", "/leave", "/routes", "/tutorial", "/credentials", "/products/category", "/products/tree", "/reports/retailers", "/reports/route-std", "/drill/category?code=cigarette", "/drill/channel?code=grocery", "/drill/calls", "/drill/login"];

test.beforeEach(async () => {
  await resetMock();
});

async function english(page: Page) {
  await page.getByRole("link", { name: "English" }).click();
  await page.goto("/overview");
}

// 1366 is the office laptop; 1024 and 360 make the tables overflow the way a CI runner's wider data or a phone does.
for (const [theme, width] of [["light", 1366], ["dark", 1366], ["sunlight", 1366], ["light", 1024], ["light", 360], ["dark", 360]] as const) {
  test(`axe: every dashboard route has no WCAG A/AA violation (${theme}, ${width}px)`, async ({ page }) => {
    test.setTimeout(300_000);
    await page.emulateMedia({ colorScheme: theme === "dark" ? "dark" : "light", reducedMotion: "reduce" });
    await page.addInitScript((th) => { try { localStorage.setItem("aron.theme", th); } catch {} }, theme);
    await loginOk(page, "tso334", "tso-pass-1");
    await english(page);
    await page.setViewportSize({ width, height: 900 });
    const failures: string[] = [];
    for (const path of DASH_PAGES) {
      const res = await page.goto(path);
      if (!res || res.status() >= 400) {
        failures.push(`${path}: HTTP ${res?.status()}`);
        continue;
      }
      await page.waitForLoadState("networkidle");
      if (new URL(page.url()).pathname !== new URL(path, "http://x").pathname) {
        failures.push(`${path}: redirected to ${page.url()}`);
        continue;
      }
      const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
      for (const v of r.violations) failures.push(`${path}: ${v.id} (${v.impact}) x${v.nodes.length} e.g. ${v.nodes[0]?.target.join(" ")}`);
    }
    expect(failures).toEqual([]);
  });
}

test("axe: the DMO home (ranking table) has no violation", async ({ page }) => {
  await loginOk(page, "dmo1", "dmo-pass-1");
  await english(page);
  await page.goto("/overview");
  await expect(page.getByTestId("table-rank")).toBeVisible();
  const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(r.violations.map((v) => `${v.id} x${v.nodes.length}`)).toEqual([]);
});

test("keyboard only: Filter opens, a zone is chosen, Escape closes; tables are reachable and CSV downloads from the keyboard", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await expect(page.getByTestId("home-filter")).toHaveAttribute("data-ready", "");
  const summary = page.getByTestId("home-filter").locator("summary");
  await summary.focus();
  await page.keyboard.press("Enter");
  const zone = page.getByTestId("scope-select-zone");
  await expect(zone).toBeVisible();
  await zone.focus();
  await page.keyboard.press("Escape");
  await expect(zone).toBeHidden();
  await expect(summary).toBeFocused();
  // The table's scroll region, then back through Print and CSV: all reachable without a pointer, in reading order.
  const region = page.getByTestId("table-sr").getByRole("region");
  await region.focus();
  await expect(region).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(page.getByTestId("print-page")).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(page.getByTestId("csv-sr-performance")).toBeFocused();
  const [dl] = await Promise.all([page.waitForEvent("download"), page.keyboard.press("Enter")]);
  expect(dl.suggestedFilename()).toBe("sr-performance.csv");
  // a "Details" link is a real link: focusable and opened with Enter
  const link = page.getByTestId("drill-cat-cigarette");
  await link.focus();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/drill\/category/);
});

test("Bangla home: digits are Bengali in every key figure", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await page.getByRole("link", { name: "বাংলা" }).click();
  await page.goto("/overview");
  await expect(page.getByTestId("dashboard-tiles")).toBeVisible();
  for (const id of ["cat-qty-cigarette", "cat-qty-bidi", "cpr-planned", "cpr-calls", "login-pct", "strike-rate", "sales-net"]) {
    const text = (await page.getByTestId(id).first().innerText()).trim();
    expect(text, id).not.toMatch(/[0-9]/);
    expect(text, id).toMatch(/[০-৯]/);
  }
  const tableText = await page.getByTestId("table-sr").innerText();
  expect(tableText.replace(/R\d+|[A-Za-z]+\d*/g, "")).not.toMatch(/[0-9]/);
});
