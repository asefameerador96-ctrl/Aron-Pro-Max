// Report page specifics in a browser: SKU prices, DSS drill, QC split and PDF, suspicious strip and trend, geofence histogram, DS-RRS print.
import { expect, test, type Page } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});
const start = async (page: Page, user = "tso334", pw = "tso-pass-1") => {
  await loginOk(page, user, pw);
  await page.getByRole("link", { name: "English" }).click();
};

test("SKU shows prices to three decimals and only the price types the role may see, with Excel", async ({ page, context }) => {
  await start(page);
  await page.goto("/reports/skus");
  await expect(page.locator('tbody tr', { hasText: "SKU-001" }).locator('[data-col="retail_price_mtk"]')).toHaveText("7.935");
  await expect(page.locator('th[data-col="trade_price_mtk"]')).toHaveCount(0);
  await expect(page.getByTestId("get-excel")).toBeVisible();
  await context.clearCookies();
  await start(page, "analyst1", "analyst-pass-1");
  await page.goto("/reports/skus");
  await expect(page.locator('th[data-col="trade_price_mtk"]')).toBeVisible();
});

test("DSS drills from a route row to its outlet-wise sales", async ({ page }) => {
  await start(page);
  await page.goto("/reports/dss");
  await expect(page.getByTestId("tile-meta")).toContainText("As of");
  await page.getByTestId("drill-link").first().click();
  await expect(page).toHaveURL(/\/reports\/by-outlet\?route=/);
  await expect(page.getByTestId("report-by-outlet")).toBeVisible();
});

test("QC report shows Market and Warehouse apart with Get Excel and Download PDF", async ({ page }) => {
  await start(page);
  await page.goto("/reports/qc");
  await expect(page.getByTestId("split-Market")).toBeVisible();
  await expect(page.getByTestId("split-Warehouse")).toBeVisible();
  await expect(page.getByTestId("get-excel")).toBeVisible();
  await page.getByTestId("download-pdf").click();
  await expect(page.getByTestId("export-job")).toBeVisible(); // PDF is always an export job
});

test("suspicious location: mock counts strip and a trend by day", async ({ page }) => {
  await start(page);
  await page.goto("/reports/suspicious-location");
  await expect(page.getByTestId("geo-strip")).toBeVisible();
  await expect(page.getByTestId("geo-mock")).toContainText("%");
  await expect(page.getByTestId("report-chart")).toBeVisible();
});

test("geofence calibration: a distance histogram", async ({ page }) => {
  await start(page);
  await page.goto("/reports/geofence-calibration");
  await expect(page.getByTestId("report-chart").locator("li")).toHaveCount(4);
  await expect(page.locator('td[data-col="force_sale_share"]').last()).toHaveText("100.0%");
});

test("DS-RRS has Get Data, Get Excel and a Print view", async ({ page }) => {
  await start(page);
  await page.goto("/reports/ds-rrs");
  await expect(page.getByTestId("get-data")).toBeVisible();
  await expect(page.getByTestId("get-excel")).toBeVisible();
  const href = await page.getByTestId("print-view").getAttribute("href");
  const res = await page.request.get(href!);
  expect(res.headers()["content-type"]).toContain("text/html");
  const html = await res.text();
  expect(html).toContain("data-totals");
  expect(res.headers()["content-security-policy"]).toContain("sandbox");
});

test("a report in Bangla uses Bengali digits and the Bangla title", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await page.goto("/reports/route-std");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("রুট অনুযায়ী এসটিডি");
  await expect(page.getByTestId("row-count")).toContainText(/[০-৯]/);
});
