// docs/31 quality bar: screenshot tests of the radius map and two core pages in the three glass tiers (A full, B lite, C solid) so a tier regression shows.
// Each test also asserts the tier's computed style (blur in A only, solid fill in C), which is the part that must not drift across machines.
import { expect, test, type Page } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

type Tier = "A" | "B" | "C";
const TIERS: Tier[] = ["A", "B", "C"];

test.beforeEach(async () => {
  await resetMock();
});

async function enter(page: Page, tier: Tier, user: "admin1" | "super1"): Promise<void> {
  await page.setViewportSize({ width: 1366, height: 900 });
  await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
  await loginOk(page, user, user === "admin1" ? "admin-pass-1" : "super-pass-1", "123456");
  await page.context().addCookies([{ name: "aron_locale", value: "en", url: "http://127.0.0.1:3100" }]);
  if (tier === "C") {
    // Chromium has no emulateMedia switch for reduced transparency, so use the DevTools protocol (the media query is the dependable C path).
    const cdp = await page.context().newCDPSession(page);
    await cdp.send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-reduced-transparency", value: "reduce" }] });
  }
}

/** Tier B is the `data-glass="lite"` stamp; React removes unknown <html> attributes on hydration, so it is set after the page settled. */
async function settle(page: Page, tier: Tier): Promise<void> {
  await page.waitForLoadState("networkidle");
  if (tier === "B") await page.evaluate(() => document.documentElement.setAttribute("data-glass", "lite"));
}

const filter = (page: Page) => page.locator("aside[aria-label]").first().evaluate((el) => ({ blur: getComputedStyle(el).backdropFilter, fill: getComputedStyle(el).backgroundColor }));

for (const tier of TIERS) {
  test(`tier ${tier}: radius map with the glass side panel`, async ({ page }) => {
    await enter(page, tier, "admin1");
    await page.goto("/admin/config/geofence?level=global&id=0");
    await settle(page, tier);
    await expect(page.getByTestId("geo-current")).toBeVisible();
    const s = await filter(page);
    if (tier === "A") expect(s.blur).toContain("blur");
    else expect(s.blur).toBe("none");
    if (tier === "C") expect(s.fill).toMatch(/^rgb\(/); // solid: no alpha channel
    else expect(s.fill).toMatch(/^rgba\(/);
    await expect(page.getByTestId("geo-current")).toHaveScreenshot(`radius-map-tier-${tier}.png`, { maxDiffPixelRatio: 0.03, animations: "disabled" });
  });

  test(`tier ${tier}: permission matrix and config home`, async ({ page }) => {
    await enter(page, tier, "super1");
    await page.goto("/admin/permissions?role=TSO");
    await settle(page, tier);
    await expect(page.getByTestId("perm-TSO")).toBeVisible();
    await expect(page.getByTestId("perm-TSO")).toHaveScreenshot(`permissions-tier-${tier}.png`, { maxDiffPixelRatio: 0.03, animations: "disabled" });
    await page.goto("/admin/config");
    await settle(page, tier);
    await expect(page).toHaveScreenshot(`config-home-tier-${tier}.png`, { maxDiffPixelRatio: 0.03, animations: "disabled", mask: [page.locator("time")], fullPage: false });
  });
}
