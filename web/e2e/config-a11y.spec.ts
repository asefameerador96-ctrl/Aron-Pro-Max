// docs/31 quality bar: an automated axe pass (WCAG 2.x A/AA, which includes the AA colour contrast rule) on every web-config page, in light and dark.
import AxeBuilder from "@axe-core/playwright";
import { expect, test, type BrowserContext } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

export const CONFIG_PAGES: { name: string; path: string }[] = [
  { name: "config-home", path: "/admin/config" },
  { name: "keys", path: "/admin/config/keys" },
  { name: "rules", path: "/admin/config/rules" },
  { name: "switches", path: "/admin/config/switches" },
  { name: "changes", path: "/admin/config/changes" },
  { name: "history", path: "/admin/config/history" },
  { name: "reach", path: "/admin/config/reach" },
  { name: "geofence", path: "/admin/config/geofence?level=global&id=0&value=150" },
  { name: "flags", path: "/admin/config/flags" },
  { name: "day", path: "/admin/config/day" },
  { name: "sync-health", path: "/admin/config/sync-health" },
  { name: "quarantine", path: "/admin/config/quarantine" },
  { name: "app-block", path: "/admin/config/app-block" },
  { name: "calendar", path: "/admin/calendar" },
  { name: "audit", path: "/admin/audit" },
  { name: "device-otps", path: "/admin/device-otps?zone=3341" },
  { name: "devices", path: "/admin/devices" },
  { name: "releases", path: "/admin/releases" },
  { name: "enrolment", path: "/admin/enrolment" },
  { name: "entry-unlocks", path: "/admin/entry-unlocks" },
  { name: "print-templates", path: "/admin/print-templates" },
  { name: "supervisor-targets", path: "/admin/supervisor-targets" },
  { name: "dues", path: "/admin/dues" },
  { name: "export-log", path: "/admin/export-log" },
  { name: "permissions", path: "/admin/permissions?role=TSO" },
  { name: "data-entry", path: "/admin/data-entry" },
  { name: "web-entry", path: "/entry/web?zone=3341&route=10231" },
  { name: "qc-entry", path: "/entry/qc" },
  { name: "final-submit", path: "/final-submit/submit" },
];

test.beforeEach(async () => {
  await resetMock();
});

async function english(context: BrowserContext) {
  await context.addCookies([{ name: "aron_locale", value: "en", url: "http://127.0.0.1:3100" }]);
}

for (const scheme of ["light", "dark"] as const) {
  test(`axe: every web-config page has no WCAG A/AA violation (${scheme})`, async ({ page, context }) => {
    test.setTimeout(240_000);
    await page.emulateMedia({ colorScheme: scheme });
    await loginOk(page, "super1", "super-pass-1", "123456");
    await english(context);
    const failures: string[] = [];
    for (const p of CONFIG_PAGES) {
      const res = await page.goto(p.path);
      if (!res || res.status() >= 400) {
        failures.push(`${p.name}: HTTP ${res?.status()}`);
        continue;
      }
      await page.waitForLoadState("networkidle");
      if (new URL(page.url()).pathname !== new URL(p.path, "http://x").pathname) {
        failures.push(`${p.name}: redirected to ${page.url()}`); // a silent redirect to login or an error page must not be scanned as the target
        continue;
      }
      const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
      for (const v of r.violations) failures.push(`${p.name}: ${v.id} (${v.impact}) x${v.nodes.length} e.g. ${v.nodes[0]?.target.join(" ")}`);
    }
    expect(failures).toEqual([]);
  });
}
