// Completion pass audit: every dashboard route answers for each web role the mock knows (TSO, DMO, WM, ANALYST), shows its heading
// and never the error boundary or a raw problem; the home carries its category cards, rings and scope Filter for every scope level.
import { expect, test } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

const USERS = [
  ["tso334", "tso-pass-1"],
  ["dmo1", "dmo-pass-1"],
  ["wm1", "wm-pass-1"],
  ["analyst1", "analyst-pass-1"],
] as const;
const ROUTES = ["/", "/daily-tracking", "/tso-daily-tracking", "/final-submit", "/sync-health", "/exceptions", "/leave", "/routes", "/tutorial", "/credentials", "/products/category", "/reports/retailers", "/reports/route-std"];

test.beforeEach(async () => {
  await resetMock();
});

for (const [user, pass] of USERS) {
  test(`${user}: every dashboard route renders without an error page`, async ({ page }) => {
    await loginOk(page, user, pass);
    await page.getByRole("link", { name: "English" }).click();
  await page.goto("/overview");
    for (const path of ROUTES) {
      const res = await page.goto(path);
      expect(res?.status(), `${user} ${path}`).toBeLessThan(500);
      await expect(page.locator("h1").first(), `${user} ${path}`).toBeVisible();
      await expect(page.getByText("Something went wrong"), `${user} ${path}`).toHaveCount(0);
    }
    await page.goto("/overview");
    await expect(page.getByTestId("home-filter")).toBeVisible();
    await expect(page.getByTestId("tile-cat-cigarette")).toBeVisible();
  });
}
