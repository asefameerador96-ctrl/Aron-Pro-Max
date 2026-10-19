// Long Bangla names (completion pass item 3): the mock returns real-length Bangla route, user and outlet names (POST /__mock/long-names).
// Every table and card must stay inside the page (tables scroll inside their own region), nothing may overlap, and a name that is
// cut shows its full text as a tooltip (title). Screenshots are attached for the owner's look.
import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
  await fetch(`${MOCK}/__mock/long-names`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ on: true }) });
});
test.afterEach(async () => {
  await fetch(`${MOCK}/__mock/long-names`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ on: false }) });
});

const PAGES = [["home", "/"], ["tracking", "/daily-tracking"], ["retailers", "/reports/retailers"], ["routes", "/routes"], ["exceptions", "/exceptions"], ["drill-calls", "/drill/calls"]] as const;
const SIZES = [["phone", 360, 740], ["laptop", 1366, 768]] as const;

for (const [name, path] of PAGES) {
  test(`long Bangla names: ${name} stays inside the page and cut names carry a tooltip`, async ({ page }, info) => {
    test.setTimeout(90_000);
    await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
    await loginOk(page, "tso334", "tso-pass-1");
    await page.getByRole("link", { name: "বাংলা" }).click();
    for (const [size, w, h] of SIZES) {
      await page.setViewportSize({ width: w, height: h });
      await page.goto(path);
      await expect(page.locator("h1").first()).toBeVisible();
      await page.waitForLoadState("networkidle");
      const file = info.outputPath(`long-${name}-${size}.png`);
      await page.screenshot({ path: file, fullPage: true });
      await info.attach(`long-${name}-${size}`, { path: file, contentType: "image/png" });
      const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(over, `${name} ${size} overflows the page by ${over}px`).toBeLessThanOrEqual(1);
      // A card title or a name cell that is visibly cut (text wider than its box) must hold the full text in a title attribute.
      const cutWithoutTip = await page.evaluate(() => [...document.querySelectorAll<HTMLElement>("h2, td, th, [data-name]")]
        .filter((el) => el.scrollWidth > el.clientWidth + 1 && getComputedStyle(el).overflow !== "visible" && !el.title && !el.querySelector("[title]"))
        .map((el) => (el.textContent ?? "").trim().slice(0, 40)));
      expect(cutWithoutTip, `${name} ${size}: cut text without a tooltip`).toEqual([]);
    }
  });
}
