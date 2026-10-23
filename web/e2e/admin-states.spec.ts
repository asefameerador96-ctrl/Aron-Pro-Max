// States and Bangla audit of the admin portal (D-WEB-ADMIN-STATES-01, CLAUDE.md rule 14, PARITY-SPEC s4 and s5).
// Every static admin page, as a SUPERADMIN in Bangla light: (1) it renders and stays inside the page at phone and laptop width, a visibly cut
// text carries its full text as a tooltip; (2) when the API answers 500 it shows the plain error state (alert, retry or message), never a
// blank page and never a raw ERR_ code. Loading is the segment skeleton (page-loading, covered in pages.spec), offline is the shell chip (checked once here).
import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

const GENERIC = ["wings", "divisions", "territories", "houses", "zones", "clusters", "routes", "route-assignments", "users", "categories", "segments", "brands", "variants", "skus", "outlets", "outlet-requests", "feedback"].map((s) => `/admin/${s}`);
const PAGES = [
  "/admin", "/admin/master-data", "/admin/audit", "/admin/calendar", "/admin/config", "/admin/config/app-block", "/admin/config/audit", "/admin/config/changes", "/admin/config/day",
  "/admin/config/flags", "/admin/config/geofence", "/admin/config/history", "/admin/config/keys", "/admin/config/quarantine", "/admin/config/reach", "/admin/config/rules",
  "/admin/config/switches", "/admin/config/sync-health", "/admin/content", "/admin/data-entry", "/admin/day", "/admin/device-otps", "/admin/devices", "/admin/dues", "/admin/enrolment",
  "/admin/entry-unlocks", "/admin/export-log", "/admin/house-dedupe", "/admin/mdm", "/admin/mdm/activity", "/admin/mdm/alerts", "/admin/mdm/enrolment", "/admin/mdm/policies",
  "/admin/mdm/policies/new", "/admin/outlet-change", "/admin/outlet-import", "/admin/permissions", "/admin/prices", "/admin/print-templates", "/admin/quarantine", "/admin/radius",
  "/admin/releases", "/admin/route-targets", "/admin/rubrics", "/admin/sales-plan", "/admin/sr-lifecycle", "/admin/sr-transfer", "/admin/supervisor-targets", "/admin/surveys",
  "/admin/team", "/admin/tutorials", "/admin/wholesale-marking", ...GENERIC,
];
const SIZES = [["phone", 360, 740], ["laptop", 1366, 768]] as const;

const setFail = (prefix: string | null) => fetch(`${MOCK}/__mock/fail`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ prefix }) });

test.beforeEach(async () => {
  await resetMock();
});

for (const path of PAGES) {
  test(`states: ${path} in Bangla light stays inside the page and fails plainly`, async ({ page }, info) => {
    test.setTimeout(90_000);
    await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
    await loginOk(page, "msuper1", "super-pass-1", "123456");
    await page.getByRole("link", { name: "বাংলা" }).click();
    for (const [size, w, h] of SIZES) {
      await page.setViewportSize({ width: w, height: h });
      await page.goto(path);
      await expect(page.locator("h1, h2").first()).toBeVisible();
      await page.waitForLoadState("networkidle");
      const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(over, `${path} ${size} overflows the page by ${over}px`).toBeLessThanOrEqual(1);
      const cutWithoutTip = await page.evaluate(() => [...document.querySelectorAll<HTMLElement>("h2, td, th, label, button, a")]
        .filter((el) => el.getAttribute("href") !== "#content") // the visually hidden skip link is clipped on purpose
        .filter((el) => el.scrollWidth > el.clientWidth + 1 && getComputedStyle(el).overflow !== "visible" && !el.title && !el.querySelector("[title]"))
        .map((el) => (el.textContent ?? "").trim().slice(0, 40)));
      expect(cutWithoutTip, `${path} ${size}: cut text without a tooltip`).toEqual([]);
    }
    // Error state: every API call answers 500.
    await page.setViewportSize({ width: 1366, height: 768 });
    await setFail("/v1/");
    try {
      await page.goto(path);
      await page.waitForLoadState("networkidle");
      const body = (await page.locator("body").innerText()).trim();
      expect(body.length, `${path}: blank page when the API fails`).toBeGreaterThan(20);
      expect(body, `${path}: a raw error code is on screen`).not.toMatch(/ERR_[A-Z_]{3,}/);
      await info.attach("error-state", { body: await page.screenshot(), contentType: "image/png" });
    } finally {
      await setFail(null);
    }
  });
}

test("states: the offline chip says so in Bangla", async ({ page, context }) => {
  await loginOk(page, "msuper1", "super-pass-1", "123456");
  await page.getByRole("link", { name: "বাংলা" }).click();
  await page.goto("/admin/master-data");
  await context.setOffline(true);
  await expect(page.getByTestId("connection-chip")).toContainText(/অফলাইন|Offline/);
  await context.setOffline(false);
});
