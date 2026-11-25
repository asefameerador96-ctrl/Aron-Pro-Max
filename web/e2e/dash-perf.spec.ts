// Completion pass item 7: performance budget of the dashboard home on a throttled phone profile (Fast 3G, 4x CPU slowdown):
// JS bytes on the wire for a cold load, LCP, and layout shift during load and after an in-place refresh. The numbers are
// attached to the report so docs/status/web-dashboard.md can record them.
import { expect, test } from "@playwright/test";
import { BUDGET, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

test("home on Fast 3G with 4x CPU: JS, LCP and layout shift stay in budget", async ({ page, context }, info) => {
  test.setTimeout(180_000);
  await page.clock.install();
  await loginOk(page, "tso334", "tso-pass-1");
  await page.getByRole("link", { name: "English" }).click();
  await page.goto("/overview");
  await page.setViewportSize({ width: 360, height: 740 });
  const cdp = await context.newCDPSession(page);
  await cdp.send("Network.enable");
  await cdp.send("Network.emulateNetworkConditions", { offline: false, latency: 562, downloadThroughput: (1.6 * 1024 * 1024) / 8, uploadThroughput: (750 * 1024) / 8 });
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: 4 });
  await cdp.send("Network.clearBrowserCache");
  await page.addInitScript(() => {
    const w = window as unknown as { __lcp: number; __cls: number; __lcpEntries: { t: number; size: number; el: string }[] };
    w.__lcp = 0;
    w.__cls = 0;
    w.__lcpEntries = [];
    new PerformanceObserver((l) => {
      for (const e of l.getEntries() as unknown as { startTime: number; size: number; element?: Element | null }[]) {
        w.__lcp = e.startTime;
        w.__lcpEntries.push({ t: Math.round(e.startTime), size: e.size, el: e.element ? `${e.element.tagName.toLowerCase()}.${String(e.element.className).slice(0, 40)}` : "" });
      }
    }).observe({ type: "largest-contentful-paint", buffered: true });
    new PerformanceObserver((l) => { for (const e of l.getEntries() as unknown as { value: number; hadRecentInput: boolean }[]) if (!e.hadRecentInput) w.__cls += e.value; }).observe({ type: "layout-shift", buffered: true });
  });
  let jsBytes = 0;
  cdp.on("Network.loadingFinished", () => {});
  const sizes = new Map<string, string>();
  cdp.on("Network.responseReceived", (e: { requestId: string; type: string }) => { if (e.type === "Script") sizes.set(e.requestId, e.requestId); });
  cdp.on("Network.loadingFinished", (e: { requestId: string; encodedDataLength: number }) => { if (sizes.has(e.requestId)) jsBytes += e.encodedDataLength; });
  // EVIDENCE ONLY (attached when an assertion below fails, never read by one): the request timeline of the measured navigation, so an outlier shows which request waited (a flaky LCP of 3628 ms
  // against 1660 ms on the retry was seen once in CI with no cause found, D-TKPI-22). It changes no number, no assertion and no retry.
  const timeline: { ms: number; ev: string; url: string; info: string }[] = [];
  let t0 = 0;
  const mark = (ts: number, ev: string, url: string, info: string) => { if (!t0) t0 = ts; timeline.push({ ms: Math.round((ts - t0) * 1000), ev, url: url.replace(/^https?:\/\/[^/]+/, "").slice(0, 80), info }); };
  const urls = new Map<string, string>();
  cdp.on("Network.requestWillBeSent", (e: { requestId: string; timestamp: number; type?: string; request: { url: string } }) => { urls.set(e.requestId, e.request.url); mark(e.timestamp, "request", e.request.url, e.type ?? ""); });
  cdp.on("Network.responseReceived", (e: { requestId: string; timestamp: number; response: { status: number; url: string } }) => { urls.set(e.requestId, e.response.url); mark(e.timestamp, "response", e.response.url, String(e.response.status)); });
  cdp.on("Network.loadingFinished", (e: { requestId: string; timestamp: number; encodedDataLength: number }) => mark(e.timestamp, "finished", urls.get(e.requestId) ?? "", `${e.encodedDataLength} B`));
  await page.goto("/", { waitUntil: "load" });
  await expect(page.getByTestId("national-dashboard")).toBeVisible({ timeout: 60_000 });
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
  const lcpEntries = await page.evaluate(() => (window as unknown as { __lcpEntries: unknown[] }).__lcpEntries);
  const result = { jsKbEncoded: Math.round(jsBytes / 1024), lcpMs: Math.round(cold.lcp), clsLoad: Number(cold.cls.toFixed(3)), clsRefreshDelta: Number((after - before).toFixed(3)) };
  await info.attach("perf-numbers", { body: JSON.stringify(result, null, 2), contentType: "application/json" });
  console.log("PERF", JSON.stringify(result));
  try {
    expect(result.jsKbEncoded, "JS on the wire (KB)").toBeLessThanOrEqual(BUDGET.jsKbGzip);
    expect(result.lcpMs, "LCP (ms)").toBeLessThanOrEqual(BUDGET.lcpMs);
    expect(result.clsLoad, "CLS on load").toBeLessThanOrEqual(BUDGET.cls);
    expect(result.clsRefreshDelta, "CLS from a refresh").toBeLessThanOrEqual(0.01);
  } catch (err) {
    await info.attach("perf-evidence-on-failure", { body: JSON.stringify({ result, lcpEntries, timeline }, null, 2), contentType: "application/json" });
    throw err;
  }
});
