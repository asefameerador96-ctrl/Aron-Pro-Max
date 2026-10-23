// Bangla light screenshots of the admin pages changed by the states audit (D-WEB-ADMIN-STATES-01, PARITY-SPEC s5): the content and
// tutorial tables sit in a scroll region, so a wide Bangla table no longer pushes the page sideways on a phone.
import { expect, test } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

for (const [name, path] of [["content", "/admin/content"], ["tutorials", "/admin/tutorials"]] as const) {
  for (const [size, w, h] of [["phone", 360, 740], ["laptop", 1366, 768]] as const) {
    test(`admin ${name} in Bangla light at ${size} width`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
      await loginOk(page, "msuper1", "super-pass-1", "123456");
      await page.getByRole("link", { name: "বাংলা" }).click();
      await page.setViewportSize({ width: w, height: h });
      await page.goto(path);
      await expect(page.getByTestId("data-table")).toBeVisible();
      await page.waitForLoadState("networkidle");
      await expect(page).toHaveScreenshot(`admin-${name}-bn-light-${size}.png`, { maxDiffPixelRatio: 0.03, animations: "disabled" });
    });
  }
}
