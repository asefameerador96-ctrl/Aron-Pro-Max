// Completion pass: screenshots of the dashboard home in light and dark at office and tablet widths, attached to the test report
// (no golden comparison yet; the web-snapshots workflow can record goldens from these once the owner signs the look off).
import { expect, test } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

for (const [scheme, w, h] of [["light", 1366, 900], ["dark", 1366, 900], ["light", 820, 1180]] as const) {
  test(`home screenshot ${scheme} ${w}x${h}`, async ({ page }, info) => {
    await page.emulateMedia({ colorScheme: scheme, reducedMotion: "reduce" });
    await loginOk(page, "tso334", "tso-pass-1"); // the helper expects the desktop shell
    await page.getByRole("link", { name: "English" }).click();
    await page.setViewportSize({ width: w, height: h });
    await expect(page.getByTestId("dashboard-tiles")).toBeVisible();
    await page.waitForLoadState("networkidle");
    const path = info.outputPath(`home-${scheme}-${w}.png`);
    await page.screenshot({ path, fullPage: true });
    await info.attach(`home-${scheme}-${w}`, { path, contentType: "image/png" });
  });
}
