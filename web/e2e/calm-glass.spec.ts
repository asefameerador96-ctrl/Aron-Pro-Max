// Calm Glass contract checks that only a browser can make (web-glass s2.2, s6, s8.1, s13, s14): the page gradient really paints
// (the body must stay transparent over the fixed -z-10 layer), every control is 48 px under a coarse pointer, the top bar gets
// its divider after 8 px of scroll, and a data table's sticky header blurs only once rows have scrolled under it.
import { expect, test, type Page } from "@playwright/test";
import { inflateSync } from "node:zlib";
import { login, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

/** The RGB of a 1 x 1 PNG (walk the chunks, inflate IDAT, skip the filter byte). */
function pixel(png: Buffer): [number, number, number] {
  let off = 8;
  const idat: Buffer[] = [];
  while (off + 8 <= png.length) {
    const len = png.readUInt32BE(off);
    const type = png.toString("ascii", off + 4, off + 8);
    if (type === "IHDR") expect([2, 6], "RGB or RGBA png").toContain(png[off + 8 + 9]);
    if (type === "IDAT") idat.push(png.subarray(off + 8, off + 8 + len));
    off += 12 + len;
  }
  const raw = inflateSync(Buffer.concat(idat));
  return [raw[1]!, raw[2]!, raw[3]!];
}

const hex = (v: string): [number, number, number] => [1, 3, 5].map((i) => parseInt(v.trim().slice(i, i + 2), 16)) as [number, number, number];

test("the page gradient and glows paint behind the content (body transparent over the fixed layer)", async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 });
  await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
  await page.goto("/login");
  await expect(page.getByTestId("login-form")).toBeVisible();
  const probe = await page.evaluate(() => ({
    body: getComputedStyle(document.body).backgroundColor,
    solid: getComputedStyle(document.documentElement).getPropertyValue("--bg-solid"),
    layer: getComputedStyle(document.querySelector(".bg-page")!).backgroundImage,
  }));
  expect(probe.body).toBe("rgba(0, 0, 0, 0)");
  expect(probe.layer).toContain("gradient");
  const corner = pixel(await page.screenshot({ clip: { x: 0, y: 0, width: 1, height: 1 }, animations: "disabled" }));
  expect(corner, `corner pixel ${corner} must not be the flat --bg-solid ${probe.solid}`).not.toEqual(hex(probe.solid));
});

test("the top bar shows its divider after 8 px of scroll, not after the bar's own height", async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 600 });
  await loginOk(page, "tso334", "tso-pass-1");
  const bar = page.getByTestId("top-bar");
  await expect(bar).toBeVisible();
  await page.evaluate(() => window.scrollTo(0, 4));
  await page.waitForTimeout(150);
  await expect(bar).not.toHaveAttribute("data-scrolled");
  await page.evaluate(() => window.scrollTo(0, 12));
  await expect(bar).toHaveAttribute("data-scrolled", "");
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect(bar).not.toHaveAttribute("data-scrolled");
});

test("a data table stamps data-stuck (blurred header) only once rows scroll under the header", async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 480 });
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.goto("/admin/devices");
  const region = page.locator(".table-scroll").first();
  await expect(region).toBeVisible();
  await expect(region).not.toHaveAttribute("data-stuck");
  const overflow = await region.evaluate((el) => el.scrollHeight > el.clientHeight);
  expect(overflow, "the viewport is short enough for the table to scroll").toBe(true);
  await region.evaluate((el) => el.scrollTo(0, 40));
  await expect(region).toHaveAttribute("data-stuck", "");
  await region.evaluate((el) => el.scrollTo(0, 0));
  await expect(region).not.toHaveAttribute("data-stuck");
});

test.describe("coarse pointer", () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });

  const CONTROLS = 'button, summary, input:not([type="hidden"]):not([type="checkbox"]):not([type="radio"]), select, textarea, .seg > a, .nv, a[class*="min-h-"], a[class*="rounded-full"]';

  async function assertTargets(page: Page, where: string): Promise<void> {
    const boxes = await page.locator(CONTROLS).evaluateAll((els) =>
      els
        .filter((el) => el.getClientRects().length > 0 && getComputedStyle(el).visibility !== "hidden")
        .map((el) => {
          const r = el.getBoundingClientRect();
          return { tag: el.tagName, text: (el.getAttribute("aria-label") ?? el.textContent ?? "").trim().slice(0, 30), w: r.width, h: r.height };
        }),
    );
    expect(boxes.length, `${where}: controls found`).toBeGreaterThan(0);
    for (const b of boxes) {
      expect(b.h, `${where}: ${b.tag} "${b.text}" height`).toBeGreaterThanOrEqual(48);
      if (!b.text) expect(b.w, `${where}: ${b.tag} icon-only width`).toBeGreaterThanOrEqual(48);
    }
  }

  test("every control is at least 48 px tall on login, the dashboard and the drawer", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByTestId("login-form")).toBeVisible();
    expect(await page.evaluate(() => matchMedia("(pointer: coarse)").matches)).toBe(true);
    expect(await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--ctl-h").trim())).toBe("max(40px, 48px)");
    await assertTargets(page, "login");
    await login(page, "tso334", "tso-pass-1");
    await expect(page.getByTestId("top-bar")).toBeVisible();
    await expect(page.getByTestId("dashboard-tiles")).toBeVisible();
    await assertTargets(page, "dashboard");
    await page.getByTestId("top-bar").getByRole("button").first().click();
    const drawer = page.locator("dialog[open]");
    await expect(drawer).toBeVisible();
    const items = await drawer.locator(".nv").evaluateAll((els) => els.map((el) => el.getBoundingClientRect().height));
    expect(items.length).toBeGreaterThan(0);
    for (const h of items) expect(h).toBeGreaterThanOrEqual(48);
  });
});
