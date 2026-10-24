// Flagged memos page (D-WEB-FLAGGED-MEMOS-01) against the seeded mock: rows and chips, the unknown flag, filters, cursor paging,
// role gate, Bangla at phone width and an axe pass.
import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});
const DAY = "/flagged-memos?from=2026-10-07&to=2026-10-07";
const english = async (page: Page) => {
  await page.getByRole("link", { name: "English" }).click();
};

test("a WM sees the day's flagged memos: chips, a voided row, a route-less row and an unknown flag as given", async ({ page }) => {
  await loginOk(page, "wm1", "wm-pass-1");
  await english(page);
  await page.goto(DAY);
  await expect(page.getByTestId("flagged-memos")).toBeVisible();
  await expect(page.getByTestId("data-table").locator("tbody tr")).toHaveCount(6);
  await expect(page.locator('[data-flag="zero_price_line"]').first()).toContainText("Zero-price line");
  await expect(page.locator('[data-flag="future_check_x"]')).toHaveText("future_check_x");
  await expect(page.locator('tr[data-row-state="void"]')).toHaveCount(1);
  await expect(page.getByTestId("flagged-no-route")).toHaveCount(1);
  await expect(page.getByTestId("flagged-end")).toBeVisible();
});

test("the flag filter narrows the list and the picker keeps the date range", async ({ page }) => {
  await loginOk(page, "wm1", "wm-pass-1");
  await english(page);
  await page.goto(DAY);
  await page.getByRole("combobox", { name: "Flag" }).selectOption("price_mismatch");
  await page.getByTestId("flagged-filter").getByRole("button", { name: "Filter" }).click();
  await expect(page).toHaveURL(/flag=price_mismatch/);
  await expect(page.getByTestId("data-table").locator("tbody tr")).toHaveCount(2);
  await expect(page.getByLabel("From")).toHaveValue("2026-10-07");
});

test("a day with no flagged memo shows the empty state", async ({ page }) => {
  await loginOk(page, "wm1", "wm-pass-1");
  await english(page);
  await page.goto("/flagged-memos?from=2026-10-01&to=2026-10-01");
  await expect(page.getByTestId("flagged-empty")).toBeVisible();
});

test("cursor paging: 55 rows are two pages, next then first page", async ({ page }) => {
  await loginOk(page, "wm1", "wm-pass-1");
  await english(page);
  await page.goto("/flagged-memos?from=2026-10-05&to=2026-10-05");
  await expect(page.getByTestId("data-table").locator("tbody tr")).toHaveCount(50);
  await page.getByTestId("flagged-next").click();
  await expect(page).toHaveURL(/cursor=/);
  await expect(page.getByTestId("data-table").locator("tbody tr")).toHaveCount(5);
  await expect(page.getByTestId("flagged-end")).toBeVisible();
  await page.getByTestId("flagged-first").click();
  await expect(page.getByTestId("data-table").locator("tbody tr")).toHaveCount(50);
});

test("a range over 92 days is explained, not sent", async ({ page }) => {
  await loginOk(page, "wm1", "wm-pass-1");
  await english(page);
  await page.goto("/flagged-memos?from=2026-01-01&to=2026-10-01");
  await expect(page.getByTestId("flagged-range-error")).toBeVisible();
  await expect(page.getByTestId("data-table")).toHaveCount(0);
});

test("a TSO sees only its territory; BRAND and ANALYST get the forbidden page and no menu entry", async ({ page }) => {
  await loginOk(page, "tso335", "tso-pass-2");
  await english(page);
  await page.goto("/flagged-memos?from=2026-10-07&to=2026-10-07");
  await expect(page.locator('[data-flag="future_check_x"]')).toHaveCount(1);
  await expect(page.getByTestId("data-table").locator("tbody tr")).toHaveCount(1);
  await page.context().clearCookies();
  for (const [u, p] of [["brand1", "brand-pass-1"], ["analyst1", "analyst-pass-1"]] as const) {
    await loginOk(page, u, p);
    await page.goto(DAY);
    await expect(page.getByTestId("forbidden")).toBeVisible();
    await expect(page.locator('a[href="/flagged-memos"]')).toHaveCount(0);
    await page.context().clearCookies();
  }
});

test("Bangla at phone width: no page overflow, Bangla labels and digits", async ({ page }) => {
  await loginOk(page, "wm1", "wm-pass-1");
  await page.setViewportSize({ width: 360, height: 800 }); // after login: the user name in the top bar is hidden on a phone
  await page.goto(DAY);
  await expect(page.getByTestId("flagged-memos")).toContainText("ফ্ল্যাগ করা মেমো");
  await expect(page.locator('[data-flag="price_mismatch"]').first()).toContainText("দাম মিলছে না");
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});

for (const width of [1366, 360]) {
  test(`axe: no WCAG A/AA violation (${width}px)`, async ({ page }) => {
    await loginOk(page, "wm1", "wm-pass-1");
    await english(page);
    await page.setViewportSize({ width, height: 900 });
    await page.goto(DAY);
    await page.waitForLoadState("networkidle");
    const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
    expect(r.violations.map((v) => `${v.id} x${v.nodes.length} ${v.nodes[0]?.target.join(" ")}`)).toEqual([]);
  });
}
