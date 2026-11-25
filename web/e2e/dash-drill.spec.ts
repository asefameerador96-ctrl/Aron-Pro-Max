// Completion pass: role extras on the home, drill-downs from the cards (scope and date kept in the URL), CSV export and the
// refresh rule (no faster than 60 s, nothing while the tab is hidden).
import { expect, test, type Page } from "@playwright/test";
import { loginOk, resetMock, hydrated } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

async function english(page: Page) {
  await page.getByRole("link", { name: "English" }).click();
  await page.goto("/overview");
}

test("TSO home has the SR table, DMO home the ranking table", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await expect(page.getByTestId("table-sr")).toBeVisible();
  await expect(page.getByTestId("table-rank")).toHaveCount(0);
  await page.context().clearCookies();
  await loginOk(page, "dmo1", "dmo-pass-1");
  await english(page);
  await expect(page.getByTestId("table-rank")).toBeVisible();
  await expect(page.getByTestId("table-sr")).toHaveCount(0);
});

test("category card opens its drill-down; CSV carries the shown rows with the unit", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await page.getByTestId("drill-cat-cigarette").click();
  await expect(page).toHaveURL(/\/drill\/category\?code=cigarette/);
  await expect(page.getByTestId("table-cat-routes")).toBeVisible();
  await expect(page.getByTestId("table-cat-routes").getByText("Stick").first()).toBeVisible();
  const [dl] = await Promise.all([page.waitForEvent("download"), page.getByTestId("csv-category-routes").click()]);
  expect(dl.suggestedFilename()).toBe("category-routes.csv");
  const text = await (await import("node:fs/promises")).readFile((await dl.path())!, "utf8");
  expect(text.startsWith("﻿")).toBe(true);
  expect(text.split("\r\n")[0]).toContain("Route");
  await page.getByTestId("drill-back").click();
  await expect(page.getByTestId("dashboard-tiles")).toBeVisible();
});

test("channel slice, CPR, strike and login cards each open their drill-down", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await page.getByTestId("slice-link-grocery").first().click();
  await expect(page).toHaveURL(/\/drill\/channel\?code=grocery/);
  await expect(page.getByTestId("table-channels")).toBeVisible();
  for (const [id, table] of [["drill-calls", "table-calls"], ["drill-strike", "table-calls"], ["drill-login", "table-not-logged-in"]] as const) {
    await page.goto("/overview");
    await hydrated(page.getByTestId(id)); // a click or fill before React attaches is lost or reverted (D-WEB-FIX-01)
    await page.getByTestId(id).click();
    await expect(page.getByTestId(table)).toBeVisible();
  }
});

test("scope and date stay in the drill-down URL and back link", async ({ page }) => {
  await loginOk(page, "dmo1", "dmo-pass-1");
  await english(page);
  const res = await page.goto("/drill/calls?date=2026-10-01");
  expect(res?.status()).toBe(200);
  await expect(page.getByTestId("drill-back")).toHaveAttribute("href", /date=2026-10-01/);
});

test("an unknown drill-down shows the not-found page", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await page.goto("/drill/nope");
  await expect(page.getByTestId("page-not-found")).toBeVisible();
});

test("auto-refresh does not poll faster than its 30 s interval", async ({ page }) => {
  // AutoRefresh re-reads the page every REFRESH_MS (30 s, D-NIGHT-SYNC-01) counted from the moment it MOUNTS. Under load that moment is not fixed in the fake clock (the earlier
  // forms started their window when the test reached it: a page that had mounted first saw its own 30 s tick inside a "0 reads" window, about 1 run in 5; a page that mounted late
  // had no tick inside the later one). So the test records the fake time at which the page registers its 30 s interval and counts from there: no read of the data before
  // mount + 30 s, a read right after it. Link prefetches (next-router-prefetch) fire once at hydration and are not data reads.
  await page.clock.install();
  await page.addInitScript(() => {
    const si = window.setInterval.bind(window);
    window.setInterval = ((fn: TimerHandler, ms?: number, ...args: unknown[]) => {
      if (ms === 30_000) (window as unknown as { __refreshMountedAt: number }).__refreshMountedAt = Date.now();
      return si(fn, ms, ...args);
    }) as typeof setInterval;
  });
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  let reads = 0;
  page.on("request", (r) => {
    if (r.headers()["rsc"] === "1" && !r.headers()["next-router-prefetch"] && new URL(r.url()).pathname === "/overview") reads++;
  });
  await page.goto("/overview");
  const mountedAt = () => page.evaluate(() => (window as unknown as { __refreshMountedAt?: number }).__refreshMountedAt ?? null);
  await expect
    .poll(mountedAt, { timeout: 15_000, message: "the page registered no 30 000 ms interval within 15 s: the auto-refresh interval is not 30 s (REFRESH_MS, D-NIGHT-SYNC-01) or the refresh did not mount" })
    .not.toBeNull();
  const mount = (await mountedAt())!;
  // stop the clock a little after the mount, so the advance below is exact (a running clock keeps moving while the calls travel)
  await page.clock.pauseAt(new Date((await page.evaluate(() => Date.now())) + 500));
  await page.waitForTimeout(800);
  const paused = await page.evaluate(() => Date.now());
  expect(paused - mount).toBeLessThan(15_000);
  await page.clock.runFor(mount + 29_999 - paused);
  // `reads` counts EVERY non-prefetch RSC request to /overview, whatever timer or event issued it (this interval, another interval or timeout, a focus or visibility
  // handler): any data read other than the one at mount + 30 s makes this assertion fail.
  expect(reads).toBe(0);
  await page.clock.runFor(1_000);
  await expect.poll(() => reads).toBeGreaterThan(0);
});
