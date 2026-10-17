// Completion pass: screenshots of the dashboard home in light and dark at office and tablet widths, attached to the test report
// (no golden comparison yet; the web-snapshots workflow can record goldens from these once the owner signs the look off).
import { expect, test } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

for (const [scheme, w, h, lang] of [["light", 1366, 900, "English"], ["dark", 1366, 900, "English"], ["light", 820, 1180, "English"], ["light", 1366, 900, "বাংলা"], ["sunlight", 1366, 900, "English"]] as const) {
  test(`home screenshot ${scheme} ${w}x${h} ${lang}`, async ({ page }, info) => {
    await page.emulateMedia({ colorScheme: scheme === "dark" ? "dark" : "light", reducedMotion: "reduce" });
    await page.addInitScript((th) => { try { localStorage.setItem("aron.theme", th); } catch {} }, scheme);
    await loginOk(page, "tso334", "tso-pass-1"); // the helper expects the desktop shell
    await page.getByRole("link", { name: lang }).click();
    await page.setViewportSize({ width: w, height: h });
    await expect(page.getByTestId("dashboard-tiles")).toBeVisible();
    await page.waitForLoadState("networkidle");
    const path = info.outputPath(`home-${scheme}-${w}-${lang === "English" ? "en" : "bn"}.png`);
    await page.screenshot({ path, fullPage: true });
    await info.attach(`home-${scheme}-${w}-${lang === "English" ? "en" : "bn"}`, { path, contentType: "image/png" });
  });
}

for (const [path, name] of [["/daily-tracking", "tracking"], ["/sync-health", "sync"], ["/reports/retailers", "report"], ["/exceptions", "exceptions"], ["/routes", "routes"]] as const) {
  test(`page screenshot ${name} dark`, async ({ page }, info) => {
    await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
    await page.addInitScript(() => { try { localStorage.setItem("aron.theme", "dark"); } catch {} });
    await loginOk(page, "tso334", "tso-pass-1");
    await page.getByRole("link", { name: "English" }).click();
    await page.goto(path);
    await page.waitForLoadState("networkidle");
    const file = info.outputPath(`${name}-dark.png`);
    await page.screenshot({ path: file, fullPage: true });
    await info.attach(`${name}-dark`, { path: file, contentType: "image/png" });
  });
}
