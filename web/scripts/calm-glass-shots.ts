// Screenshot matrix for the look-and-fix rounds: /login, / (dashboard, logged in through the mock) and /admin at 1366x768
// and 390x844, light and dark. Run from web/: npx tsx <this file> <outDir> [baseUrl]
import { chromium, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { join } from "node:path";

const out = process.argv[2] ?? "shots";
const base = process.argv[3] ?? "http://localhost:3000"; // localhost: Next dev blocks its client resources for other origins
mkdirSync(out, { recursive: true });

async function login(page: Page, user: string, pass: string, totp?: string) {
  await page.goto(`${base}/login`);
  // Wait until React has attached (a native submit before hydration would put the fields in the URL). An inline arrow only: tsx
  // injects its __name helper into named inner functions, and a string predicate is eval, which the CSP forbids.
  await page.waitForFunction(() => Object.keys(document.querySelector('[data-testid="login-form"]') ?? {}).some((k) => k.startsWith("__reactProps$")), undefined, { timeout: 60_000 });
  await page.getByTestId("login-form").locator('input[name="username"]').fill(user);
  await page.getByTestId("login-form").locator('input[name="password"]').fill(pass);
  await page.getByTestId("login-form").locator('button[type="submit"]').click();
  if (totp) {
    await page.getByTestId("mfa-form").locator('input[name="code"]').fill(totp);
    await page.getByTestId("mfa-form").locator('button[type="submit"]').click();
  }
  await page.getByTestId("who").or(page.getByTestId("nav-open")).first().waitFor({ state: "attached", timeout: 60_000 });
}

(async () => {
  const browser = await chromium.launch();
  for (const [w, h, tag] of [
    [1366, 768, "desk"],
    [390, 844, "phone"],
  ] as const) {
    for (const scheme of ["light", "dark"] as const) {
      const ctx = await browser.newContext({ viewport: { width: w, height: h }, colorScheme: scheme, locale: "en-GB" });
      await ctx.addInitScript((s: string) => {
        try {
          localStorage.setItem("aron.theme", s);
        } catch {}
      }, scheme);
      const page = await ctx.newPage();
      const shot = (name: string, full = false) => page.screenshot({ path: join(out, `${name}-${tag}-${scheme}.png`), fullPage: full });
      await page.goto(`${base}/login`);
      await page.waitForLoadState("networkidle");
      await shot("login");
      // dashboard as the seeded TSO (English)
      await login(page, "tso334", "tso-pass-1");
      await page.goto(`${base}/api/bff/locale?l=en&next=%2F`);
      await page.waitForLoadState("networkidle");
      await page.waitForTimeout(400);
      await shot("dashboard");
      await shot("dashboard-full", true);
      await page.goto(`${base}/api/bff/locale?l=bn&next=%2F`);
      await page.waitForLoadState("networkidle");
      await page.waitForTimeout(400);
      await shot("dashboard-bn");
      if (tag === "phone") {
        await page.getByTestId("nav-open").click();
        await page.waitForTimeout(400);
        await shot("drawer");
        await page.keyboard.press("Escape");
      }
      await page.getByTestId("theme-menu").locator("summary").click();
      await page.waitForTimeout(300);
      await shot("theme-menu");
      await page.keyboard.press("Escape");
      // admin as admin1 (TOTP 123456)
      await page.goto(`${base}/api/bff/logout`).catch(() => undefined);
      await page.request.post(`${base}/api/bff/logout`).catch(() => undefined);
      await login(page, "admin1", "admin-pass-1", "123456");
      await page.goto(`${base}/api/bff/locale?l=en&next=%2Fadmin`);
      await page.waitForLoadState("networkidle");
      await page.waitForTimeout(400);
      await shot("admin");
      await page.goto(`${base}/admin/config`);
      await page.waitForLoadState("networkidle");
      await page.waitForTimeout(400);
      await shot("config");
      await page.goto(`${base}/admin/devices`);
      await page.waitForLoadState("networkidle");
      await page.waitForTimeout(400);
      await shot("devices");
      await ctx.close();
    }
  }
  await browser.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
