// Completion pass item 7: performance budget of the dashboard home on a throttled phone profile (Fast 3G, 4x CPU slowdown):
// JS bytes on the wire for a cold load, LCP, and layout shift during load and after an in-place refresh. The numbers are
// attached to the report so docs/status/web-dashboard.md can record them.
import { expect, test } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

export const BUDGET = { jsKbGzip: 300, lcpMs: 3500, cls: 0.1 };

test.beforeEach(async () => {
  await resetMock();
});

test("home on Fast 3G with 4x CPU: JS, LCP and layout shift stay in budget", async ({ page, context }, info) => {
  test.setTimeout(180_000);
  await page.clock.install();
  await loginOk(page, "tso334", "tso-pass-1");
  await page.getByRole("link", { name: "English" }).click();
  await page.setViewportSize({ width: 360, height: 740 });
  const cdp = await context.newCDPSession(page);
  await cdp.send("Network.enable");
  await cdp.send("Network.emulateNetworkConditions", { offline: false, latency: 562, downloadThroughput: (1.6 * 1024 * 1024) / 8, uploadThroughput: (750 * 1024) / 8 });
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: 4 });
  await cdp.send("Network.clearBrowserCache");
  await page.addInitScript(() => {
    const w = window as unknown as { __lcp: number; __cls: number };
    w.__lcp = 0;
    w.__cls = 0;
    new PerformanceObserver((l) => { for (const e of l.getEntries()) w.__lcp = e.startTime; }).observe({ type: "largest-contentful-paint", buffered: true });
    new PerformanceObserver((l) => { for (const e of l.getEntries() as unknown as { value: number; hadRecentInput: boolean }[]) if (!e.hadRecentInput) w.__cls += e.value; }).observe({ type: "layout-shift", buffered: true });
  });
  let jsBytes = 0;
  cdp.on("Network.loadingFinished", () => {});
  const sizes = new Map<string, string>();
  cdp.on("Network.responseReceived", (e: { requestId: string; type: string }) => { if (e.type === "Script") sizes.set(e.requestId, e.requestId); });
  cdp.on("Network.loadingFinished", (e: { requestId: string; encodedDataLength: number }) => { if (sizes.has(e.requestId)) jsBytes += e.encodedDataLength; });
  await page.goto("/", { waitUntil: "load" });
  await expect(page.getByTestId("dashboard-tiles")).toBeVisible({ timeout: 60_000 });
  await page.waitForLoadState("networkidle");
  const cold = await page.evaluate(() => { const w = window as unknown as { __lcp: number; __cls: number }; return { lcp: w.__lcp, cls: w.__cls }; });
  // an in-place refresh must not move anything
  const before = await page.evaluate(() => (window as unknown as { __cls: number }).__cls);
  let refreshes = 0;
  page.on("request", (r) => { if (r.headers()["rsc"] === "1" && new URL(r.url()).pathname === "/") refreshes++; });
  await page.clock.runFor(61_000);
  await expect.poll(() => refreshes, { timeout: 30_000 }).toBeGreaterThan(0);
  await page.waitForLoadState("networkidle");
  await page.waitForTimeout(2000);
  const after = await page.evaluate(() => (window as unknown as { __cls: number }).__cls);
  const result = { jsKbEncoded: Math.round(jsBytes / 1024), lcpMs: Math.round(cold.lcp), clsLoad: Number(cold.cls.toFixed(3)), clsRefreshDelta: Number((after - before).toFixed(3)) };
  await info.attach("perf-numbers", { body: JSON.stringify(result, null, 2), contentType: "application/json" });
  console.log("PERF", JSON.stringify(result));
  expect(result.jsKbEncoded, "JS on the wire (KB)").toBeLessThanOrEqual(BUDGET.jsKbGzip);
  expect(result.lcpMs, "LCP (ms)").toBeLessThanOrEqual(BUDGET.lcpMs);
  expect(result.clsLoad, "CLS on load").toBeLessThanOrEqual(BUDGET.cls);
  expect(result.clsRefreshDelta, "CLS from a refresh").toBeLessThanOrEqual(0.01);
});
