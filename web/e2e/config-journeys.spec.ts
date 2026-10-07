// docs/31 quality bar, web-config critical flows in a real browser against the mock: config change request -> approval -> rollback,
// permission matrix edit, geofence radius change with blast-radius preview, Web Entry class split, OTP panel.
import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { hydrated, loginOk, MOCK, resetMock } from "./helpers";

interface MockState { webEntries: { lines: { sale_qty_base: number; memo_count: number; class_qty_base: Record<string, number> }[] }[]; otps: unknown[]; audit: { action: string }[] }
const mockState = async (): Promise<MockState> => (await fetch(`${MOCK}/__mock/state`)).json() as Promise<MockState>;

const REASON = "Quality bar journey: documented reason";

test.beforeEach(async () => {
  await resetMock();
});

async function english(context: BrowserContext) {
  await context.addCookies([{ name: "aron_locale", value: "en", url: "http://127.0.0.1:3100" }]);
}

// Login stores the user's own language (Bangla); the journeys read English labels, so switch the cookie after login.
async function asAdmin(page: Page) {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await english(page.context());
}
async function asSuper(page: Page) {
  await loginOk(page, "super1", "super-pass-1", "123456");
  await english(page.context());
}

test("change request: a risk 2 change waits for a different SUPERADMIN, then rollback creates a new version", async ({ page, browser }) => {
  await asAdmin(page);
  await page.goto("/admin/config/geofence?level=global&id=0");
  await expect(page.getByTestId("current-radius")).toContainText("100");
  const form = page.getByTestId("radius-form");
  await hydrated(form.getByRole("button"));
  await form.locator("input").first().fill("150");
  await form.locator("textarea").fill(REASON);
  await form.getByRole("button").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();

  // Still 100 m: the change is pending approval, not applied.
  await page.reload();
  await expect(page.getByTestId("current-radius")).toContainText("100");
  await page.goto("/admin/config/changes?status=pending_approval");

  // The maker cannot approve their own request: the portal offers cancel only.
  await expect(page.getByTestId("change-1")).toBeVisible();
  await expect(page.getByTestId("approve-1")).toHaveCount(0);
  await expect(page.getByTestId("cancel-1")).toBeVisible();

  // A different SUPERADMIN approves.
  const ctx2 = await browser.newContext();
  const page2 = await ctx2.newPage();
  await asSuper(page2);
  await page2.goto("/admin/config/changes?status=pending_approval");
  await hydrated(page2.getByTestId("approve-1").getByRole("button").first());
  await page2.getByTestId("approve-1").getByRole("button").first().click();
  await page2.getByTestId("approve-1").locator("textarea").fill(REASON);
  await page2.getByTestId("approve-1").getByRole("button").first().click();
  // An approved change leaves the pending list, so its row (and its banner) goes away: that is the visible condition to wait on.
  await expect(page2.getByTestId("approve-1")).toHaveCount(0);

  await page.goto("/admin/config/geofence?level=global&id=0");
  await expect(page.getByTestId("current-radius")).toContainText("150");

  // Rollback to the first version: a NEW version is committed, history only grows.
  await page.goto("/admin/config/history");
  await expect(page.getByTestId("version-319")).toBeVisible();
  await hydrated(page.getByTestId("rollback-318").getByRole("button").first());
  await page.getByTestId("rollback-318").getByRole("button").first().click();
  await page.getByTestId("rollback-318").locator("textarea").fill(REASON);
  await page.getByTestId("rollback-318").getByRole("button").first().click();
  await expect(page.getByTestId("rollback-318").getByTestId("form-ok")).toBeVisible();
  await page.reload();
  await expect(page.getByTestId("version-320")).toBeVisible();
  await expect(page.getByTestId("version-319")).toBeVisible();
  await page.goto("/admin/config/geofence?level=global&id=0");
  await expect(page.getByTestId("current-radius")).toContainText("100");
  await ctx2.close();
});

test("geofence: the radius what-if previews the visits that flip before anything is saved, with the blast radius", async ({ page }) => {
  await asAdmin(page);
  await page.goto("/admin/config/geofence?level=global&id=0");
  await expect(page.getByTestId("blast")).toContainText("6");
  await expect(page.getByTestId("calibration")).toBeVisible();
  await page.goto("/admin/config/geofence?level=global&id=0&value=150&days=30");
  await expect(page.getByTestId("whatif-result")).toContainText("900");
  // Previewing changed nothing: the stored radius is still 100.
  await expect(page.getByTestId("current-radius")).toContainText("100");
  await expect(page.getByTestId("geo-current")).toBeVisible();
  const state = await mockState();
  expect(state.audit.filter((a) => a.action.startsWith("config."))).toHaveLength(0); // previewing wrote no change request
});

test("permission matrix: only a SUPERADMIN edits a role and the edit is a pending C3 request", async ({ page, browser }) => {
  await asAdmin(page);
  await page.goto("/admin/permissions?role=TSO");
  await expect(page.getByTestId("perm-TSO").getByRole("checkbox", { name: "reports export" })).toBeDisabled(); // an ADMIN reads the matrix; only a SUPERADMIN edits
  const ctx2 = await browser.newContext();
  const page2 = await ctx2.newPage();
  await asSuper(page2);
  await page2.goto("/admin/permissions?role=TSO");
  const editor = page2.getByTestId("perm-TSO");
  await expect(editor).toBeVisible();
  const box = editor.getByRole("checkbox", { name: "reports export" });
  await expect(box).not.toBeChecked();
  await hydrated(editor.getByRole("button", { name: "Request this change" }));
  await box.check();
  await editor.locator("textarea").fill(REASON);
  await editor.getByRole("button", { name: "Request this change" }).click();
  await expect(editor.getByTestId("form-ok")).toBeVisible();
  await page2.goto("/admin/config/changes?status=pending_approval");
  await expect(page2.getByRole("cell", { name: REASON }).first()).toBeVisible();
  // Pending means pending: the matrix itself is unchanged until a second SUPERADMIN approves.
  await page2.goto("/admin/permissions?role=TSO");
  await expect(page2.getByTestId("perm-TSO").getByRole("checkbox", { name: "reports export" })).not.toBeChecked();
  await ctx2.close();
});

test("web entry: the sale splits across the zone's classes, a wrong split is refused, a re-save needs a reason", async ({ page }) => {
  await asAdmin(page);
  await page.goto("/entry/web?zone=3341&route=10231");
  const grid = page.getByTestId("web-entry-grid");
  await expect(grid).toBeVisible();
  const first = grid.locator('[data-testid^="sku-"]').first();
  const inputs = first.locator("input");
  await hydrated(grid.getByRole("button", { name: "Save" }));
  // columns: issue, return, class 11, class 12, memos
  await inputs.nth(0).fill("100");
  await inputs.nth(1).fill("10");
  await expect(first.locator('[data-testid^="sale-"]')).toHaveText("90");
  await inputs.nth(2).fill("50");
  await inputs.nth(3).fill("30"); // 80 != 90
  await inputs.nth(4).fill("3");
  await page.locator("#calls").fill("2");
  await grid.getByRole("button", { name: "Save" }).click();
  await expect(grid.getByRole("alert").filter({ hasText: /class|sale|total|sum/i }).first()).toBeVisible(); // the split error, nothing was sent
  expect((await mockState()).webEntries).toHaveLength(0);
  await inputs.nth(3).fill("40"); // 50 + 40 = 90
  await grid.getByRole("button", { name: "Save" }).click();
  // The saved split reaches the server. (The grid remounts after a save, so the banner is not a stable signal: wait on the server state.)
  await expect.poll(async () => (await mockState()).webEntries.length).toBe(1);
  const saved = await mockState();
  expect(saved.webEntries[0]!.lines[0]).toMatchObject({ sale_qty_base: 90, class_qty_base: { "11": 50, "12": 40 } });
  await page.reload();
  const again = page.getByTestId("web-entry-grid");
  const row = again.locator('[data-testid^="sku-"]').first().locator("input");
  await expect(row.nth(0)).toHaveValue("100"); // the saved entry is reloaded, split intact
  await hydrated(again.getByRole("button", { name: "Save" }));
  await row.nth(4).fill("4"); // memo count only: the split stays valid
  await again.getByRole("button", { name: "Save" }).click();
  await expect(again.getByText("Write at least 10 characters.")).toBeVisible(); // a re-save is refused without a reason
  await again.locator("textarea").fill(REASON);
  await again.getByRole("button", { name: "Save" }).click();
  await expect.poll(async () => (await mockState()).webEntries[0]?.lines[0]?.memo_count).toBe(4);
});

test("OTP panel: lists the zone's pending OTPs and an admin issues a fresh one with a reason", async ({ page }) => {
  await asAdmin(page);
  await page.goto("/admin/device-otps?zone=3341");
  await expect(page.getByTestId("otp-value").first()).toBeVisible();
  await expect(page.getByText("E-1001").first()).toBeVisible();
  const issue = page.getByTestId("issue-1001");
  await hydrated(issue.getByRole("button").first());
  await issue.getByRole("button").first().click();
  await issue.locator("textarea").fill(REASON);
  await issue.getByRole("button").first().click();
  await expect(issue.getByTestId("form-ok")).toBeVisible();
  await expect.poll(async () => (await mockState()).otps.length).toBe(2);
  await page.goto("/admin/device-otps");
  await expect(page.getByTestId("choose-zone")).toBeVisible();
});
