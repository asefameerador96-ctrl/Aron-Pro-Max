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

// The matrix of the completion pass (item 4): phone / tablet / desktop x light / dark / sunlight, for the home and each drill-down.
const SIZES = [["phone", 390, 844], ["tablet", 820, 1180], ["desktop", 1366, 900]] as const;
const MATRIX_PAGES = [["home", "/"], ["drill-category", "/drill/category?code=cigarette"], ["drill-channel", "/drill/channel"], ["drill-calls", "/drill/calls"], ["drill-login", "/drill/login"]] as const;
for (const theme of ["light", "dark", "sunlight"] as const) {
  for (const [name, path] of MATRIX_PAGES) {
    test(`matrix ${name} ${theme}`, async ({ page }, info) => {
      test.setTimeout(120_000);
      await page.emulateMedia({ colorScheme: theme === "dark" ? "dark" : "light", reducedMotion: "reduce" });
      await page.addInitScript((th) => { try { localStorage.setItem("aron.theme", th); } catch {} }, theme);
      await loginOk(page, "tso334", "tso-pass-1");
      await page.getByRole("link", { name: "English" }).click();
      for (const [size, w, h] of SIZES) {
        await page.setViewportSize({ width: w, height: h });
        await page.goto(path);
        await expect(page.locator("h1").first()).toBeVisible();
        await page.waitForLoadState("networkidle");
        const file = info.outputPath(`${name}-${theme}-${size}.png`);
        await page.screenshot({ path: file, fullPage: true });
        await info.attach(`${name}-${theme}-${size}`, { path: file, contentType: "image/png" });
        // no horizontal page scroll at any size (tables scroll inside their own region)
        const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
        expect(over, `${name} ${theme} ${size} overflows the page by ${over}px`).toBeLessThanOrEqual(1);
      }
    });
  }
}
