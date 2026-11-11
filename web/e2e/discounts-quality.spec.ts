// Quality bar of the discount screens (docs/31, portal-discount-web D-DISC-WEB-04): an axe pass (WCAG 2.x A/AA incl. colour contrast) on
// every screen state in light and dark and in Bangla, and the dashboard's performance budget (Fast 3G, 4x CPU, phone width) on the list.
import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { BUDGET, hydrated, loginOk, resetMock } from "./helpers";


test.beforeEach(async () => {
  await resetMock();
});

const PAGES = [
  { name: "list", path: "/admin/discounts" },
  { name: "list-filtered-empty", path: "/admin/discounts?q=nothing-like-this" },
  { name: "detail-draft", path: "/admin/discounts/2" },
  { name: "detail-ended", path: "/admin/discounts/3" },
  { name: "new", path: "/admin/discounts/new" },
  { name: "edit", path: "/admin/discounts/2/edit" },
  { name: "edit-odd-quantity", path: "/admin/discounts/4/edit" },
] as const;

async function scan(page: Page, label: string, failures: string[]) {
  const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  for (const v of r.violations) failures.push(`${label}: ${v.id} (${v.impact}) x${v.nodes.length} e.g. ${v.nodes[0]?.target.join(" ")}`);
}

for (const [scheme, lang] of [["light", "en"], ["dark", "en"], ["light", "bn"]] as const) {
  test(`axe: every discount screen has no WCAG A/AA violation (${scheme}, ${lang})`, async ({ page, context }) => {
    test.setTimeout(240_000);
    await page.emulateMedia({ colorScheme: scheme });
    await loginOk(page, "super1", "super-pass-1", "123456");
    await context.addCookies([{ name: "aron_locale", value: lang, url: "http://127.0.0.1:3100" }]);
    const failures: string[] = [];
    for (const p of PAGES) {
      const res = await page.goto(p.path);
      if (!res || res.status() >= 400) {
        failures.push(`${p.name}: HTTP ${res?.status()}`);
        continue;
      }
      await page.waitForLoadState("networkidle");
      if (new URL(page.url()).pathname !== new URL(p.path, "http://x").pathname) {
        failures.push(`${p.name}: redirected to ${page.url()}`);
        continue;
      }
      await scan(page, p.name, failures);
    }
    // the states that only exist after an action: the open status panel, form errors, and the all-products warning
    await page.goto("/admin/discounts/2");
    await page.waitForLoadState("networkidle");
    await hydrated(page.getByTestId("offer-status-panel")); // a click before React attaches is lost
    await page.getByTestId("os-active").click();
    await expect(page.getByTestId("os-form")).toBeVisible();
    await page.getByTestId("os-confirm").click(); // reason missing: the error text is shown
    await expect(page.getByTestId("error-os-reason")).toBeVisible();
    await scan(page, "status-panel-open-with-error", failures);
    await page.goto("/admin/discounts/new");
    await page.waitForLoadState("networkidle");
    await hydrated(page.getByTestId("offer-form")); // before hydration a submit is a native one that reloads the page
    await expect(page.getByTestId("of-products-all")).toBeVisible();
    await page.getByTestId("of-submit").click();
    await expect(page.getByTestId("error-of-code")).toBeVisible();
    await scan(page, "new-with-errors", failures);
    expect(failures).toEqual([]);
  });
}

// The master-data hub is the reference: it measures the admin shell alone, so a miss can be told apart (shell or this screen).
for (const [name, path, ready] of [["programme list", "/admin/discounts", "discounts-page"], ["reference: master-data hub", "/admin/master-data", ""], ["reference: SKU table (shared DataTable)", "/admin/skus", ""]] as const) {
test(`performance: ${name} on Fast 3G with 4x CPU`, async ({ page, context }, info) => {
  test.setTimeout(180_000);
  await loginOk(page, "super1", "super-pass-1", "123456");
  await context.addCookies([{ name: "aron_locale", value: "en", url: "http://127.0.0.1:3100" }]);
  await page.setViewportSize({ width: 360, height: 740 });
  const cdp = await context.newCDPSession(page);
  await cdp.send("Network.enable");
  await cdp.send("Network.emulateNetworkConditions", { offline: false, latency: 562, downloadThroughput: (1.6 * 1024 * 1024) / 8, uploadThroughput: (750 * 1024) / 8 });
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: 4 });
  await cdp.send("Network.clearBrowserCache");
  await page.addInitScript(() => {
    const w = window as unknown as { __lcp: number; __cls: number; __lcpEl: string };
    w.__lcp = 0;
    w.__lcpEl = "";
    w.__cls = 0;
    new PerformanceObserver((l) => { for (const e of l.getEntries()) { w.__lcp = e.startTime; const el = (e as unknown as { element?: Element }).element; w.__lcpEl = el ? `${el.tagName.toLowerCase()}${el.getAttribute("data-testid") ? `[${el.getAttribute("data-testid")}]` : ""}` : ""; } }).observe({ type: "largest-contentful-paint", buffered: true });
    new PerformanceObserver((l) => { for (const e of l.getEntries() as unknown as { value: number; hadRecentInput: boolean }[]) if (!e.hadRecentInput) w.__cls += e.value; }).observe({ type: "layout-shift", buffered: true });
  });
  let jsBytes = 0;
  const scripts = new Set<string>();
  cdp.on("Network.responseReceived", (e: { requestId: string; type: string }) => { if (e.type === "Script") scripts.add(e.requestId); });
  cdp.on("Network.loadingFinished", (e: { requestId: string; encodedDataLength: number }) => { if (scripts.has(e.requestId)) jsBytes += e.encodedDataLength; });
  await page.goto(path, { waitUntil: "load" });
  if (ready) await expect(page.getByTestId(ready)).toBeVisible({ timeout: 60_000 });
  else await expect(page.locator("h1").first()).toBeVisible({ timeout: 60_000 });
  await page.waitForLoadState("networkidle");
  const m = await page.evaluate(() => { const w = window as unknown as { __lcp: number; __cls: number; __lcpEl: string }; return { lcp: w.__lcp, cls: w.__cls, el: w.__lcpEl }; });
  const result = { jsKbEncoded: Math.round(jsBytes / 1024), lcpMs: Math.round(m.lcp), cls: Number(m.cls.toFixed(3)), lcpElement: m.el };
  await info.attach("perf-numbers", { body: JSON.stringify(result, null, 2), contentType: "application/json" });
  console.log("PERF", name, JSON.stringify(result));
  if (!ready) return; // the reference is recorded, not asserted
  expect(result.jsKbEncoded, "JS on the wire (KB)").toBeLessThanOrEqual(BUDGET.jsKbGzip);
  expect(result.lcpMs, "LCP (ms)").toBeLessThanOrEqual(BUDGET.lcpMs);
  expect(result.cls, "CLS on load").toBeLessThanOrEqual(BUDGET.cls);
});
}
