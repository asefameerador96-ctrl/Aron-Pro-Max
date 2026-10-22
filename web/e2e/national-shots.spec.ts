// Screenshot goldens of the national dashboard pages (docs/34 s2). A fixed past date makes the mock figures and stamps deterministic.
// Goldens are recorded on the Linux runner by the web-snapshots workflow (default spec list), never locally.
import { expect, test } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

const DAY = "2026-10-01";

test.beforeEach(async ({ page }) => {
  await resetMock();
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginOk(page, "analyst1", "analyst-pass-1");
  await page.getByRole("link", { name: "English" }).click();
});

test("national dashboard cards", async ({ page }) => {
  await page.goto(`/?date=${DAY}`);
  await expect(page.getByTestId("nat-grid")).toBeVisible();
  await page.waitForLoadState("networkidle");
  await expect(page.getByTestId("nat-grid")).toHaveScreenshot("national-grid.png", { maxDiffPixelRatio: 0.03, animations: "disabled" });
});

test("route map page", async ({ page }) => {
  await page.goto(`/dashboard/route-map/100?date=${DAY}`);
  await expect(page.getByTestId("route-map-page")).toBeVisible();
  await expect(page.getByTestId("route-map-page")).toHaveScreenshot("route-map.png", { maxDiffPixelRatio: 0.03, animations: "disabled" });
});

test("sale position page", async ({ page }) => {
  await page.goto(`/dashboard/sale-position/100000?date=${DAY}`);
  await expect(page.getByTestId("sale-position-page")).toBeVisible();
  await expect(page.getByTestId("sale-position-page")).toHaveScreenshot("sale-position.png", { maxDiffPixelRatio: 0.03, animations: "disabled" });
});
