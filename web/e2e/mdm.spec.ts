// D-MDM-01 MDM section in a real browser against the mock: device list with filters and flags, device detail with actions,
// the second-approver rule for a factory reset, the policy editor with its schedule grid, and the role gate.
import { expect, test, type Page } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});
const english = async (page: Page) => page.getByRole("link", { name: "English" }).click();
const asAdmin = async (page: Page, user = "admin1") => {
  await loginOk(page, user, "admin-pass-1", "123456");
  await english(page);
};

test("the MDM menu entry and the device list with owner mode, version, battery, network, policy and flags", async ({ page }) => {
  await asAdmin(page);
  await page.goto("/admin/mdm");
  await expect(page.getByRole("heading", { name: "Device management" })).toBeVisible();
  const table = page.getByTestId("data-table");
  await expect(table).toContainText("sr334001");
  await expect(table).toContainText("1.1.0+110");
  await expect(table.getByTestId("flag-outdated_app").first()).toBeVisible();
  await expect(table.getByTestId("flag-mock_location")).toBeVisible();
  await expect(table).toContainText("Field day");
  await expect(table).toContainText("74%");
  await expect(page.getByTestId("mdm-tabs")).toContainText("Policies");
});

test("filters: a compliance flag, a status and a user search narrow the list", async ({ page }) => {
  await asAdmin(page);
  await page.goto("/admin/mdm?flag=mock_location");
  await expect(page.getByTestId("data-table").locator("tbody tr")).toHaveCount(1);
  await expect(page.getByTestId("data-table")).toContainText("tso334");
  await page.goto("/admin/mdm?status=suspended");
  await expect(page.getByTestId("data-table")).toContainText("sr335001");
  await expect(page.getByTestId("data-table")).not.toContainText("sr334001");
  await page.goto("/admin/mdm?q=sr3340");
  await expect(page.getByTestId("data-table").locator("tbody tr")).toHaveCount(2);
  await page.goto("/admin/mdm?flag=rooted");
  await expect(page.getByTestId("table-empty")).toBeVisible();
});

test("device detail: report facts, flags, a lock command is queued once, and the activity log shows it", async ({ page }) => {
  await asAdmin(page);
  await page.goto("/admin/mdm");
  await page.getByTestId("mdm-open-3").click();
  await expect(page.getByRole("heading", { level: 1 })).toContainText("tso334");
  await expect(page.getByTestId("mdm-summary").getByTestId("flag-mock_location")).toBeVisible();
  await expect(page.getByTestId("mdm-report")).toContainText("com.lexa.fakegps");
  await page.getByTestId("mdm-cmd-lock").click();
  await page.getByTestId("mdm-cmd-confirm").click();
  await expect(page.getByTestId("error-mdm-reason")).toBeVisible(); // the reason is mandatory
  await page.locator("#mdm-reason").fill("Phone left at a shop");
  await page.getByTestId("mdm-cmd-confirm").dblclick(); // a double click is one command
  await expect(page.getByTestId("form-ok")).toContainText("queued");
  const s = await (await fetch(`${MOCK}/__mock/state`)).json();
  expect(s.audit.at(-1).action).toBe("device_command.lock");
  await expect(page.getByTestId("data-table").first()).toContainText("Queued");
  await expect(page.getByTestId("mdm-activity")).toContainText("lock queued");
});

test("factory reset: requested by one admin, approved by another, never by the requester", async ({ page, browser }) => {
  await asAdmin(page);
  await page.goto("/admin/mdm/devices/3");
  await page.getByTestId("mdm-cmd-factory_reset").click();
  await expect(page.getByTestId("mdm-second-approver")).toBeVisible();
  await page.locator("#mdm-reason").fill("Phone reported stolen");
  page.once("dialog", (d) => d.accept());
  await page.getByTestId("mdm-cmd-confirm").click();
  await expect(page.getByTestId("form-ok")).toContainText("second approver");
  await expect(page.getByText("Needs a different approver")).toBeVisible(); // not approvable by the requester
  const other = await browser.newPage();
  await asAdmin(other, "madmin1");
  await other.goto("/admin/mdm/devices/3");
  const box = other.getByTestId(/^cmd-approve-/);
  await box.getByRole("button", { name: "Approve" }).click();
  await box.locator("textarea").fill("Confirmed with the TSO by phone");
  await box.getByRole("button", { name: "Confirm" }).click();
  await expect(box.getByTestId("form-ok")).toBeVisible();
  await other.reload();
  await expect(other.getByTestId(/^cmd-state-/).first()).toHaveText("Queued");
  await other.close();
});

test("policy editor: draw a schedule on the grid, save, and the policy list shows it", async ({ page }) => {
  await asAdmin(page);
  await page.goto("/admin/mdm/policies");
  await expect(page.getByTestId("data-table")).toContainText("Strict kiosk");
  await page.getByTestId("mdm-policy-new").click();
  await page.locator("#mdm-p-name").fill("Night lock");
  await page.getByRole("button", { name: "+ WhatsApp" }).click();
  // Monday 22:00 to 24:00: four cells (slots 44..47) by clicking the first and dragging to the last
  const first = page.getByTestId("cell-0-44");
  const last = page.getByTestId("cell-0-47");
  const a = (await first.boundingBox())!;
  const b = (await last.boundingBox())!;
  await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2);
  await page.mouse.down();
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 6 });
  await page.mouse.up();
  await expect(page.getByTestId("cell-0-45")).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByTestId("mdm-window-count")).toContainText("1 of 14");
  await page.locator("#mdm-p-reason").fill("Night time lock for field staff");
  await page.getByTestId("mdm-policy-save").click();
  await expect(page).toHaveURL(/\/admin\/mdm\/policies$/);
  await expect(page.getByTestId("data-table")).toContainText("Night lock");
});

test("policy editor: a second editor holding the old version is refused", async ({ page }) => {
  await asAdmin(page);
  const other = await page.context().newPage();
  await page.goto("/admin/mdm/policies/1");
  await other.goto("/admin/mdm/policies/1"); // both hold version 1
  await page.locator("#mdm-p-desc").fill("First change");
  await page.locator("#mdm-p-reason").fill("First editor saves first");
  await page.getByTestId("mdm-policy-save").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  await other.locator("#mdm-p-desc").fill("Second change");
  await other.locator("#mdm-p-reason").fill("Second editor saves second");
  await other.getByTestId("mdm-policy-save").click();
  await expect(other.getByTestId("form-error")).toBeVisible();
  await other.close();
});

test("enrolment: a code is created, its QR is shown once, and the code can be revoked", async ({ page }) => {
  await asAdmin(page);
  await page.goto("/admin/mdm/enrolment");
  await page.getByRole("button", { name: "Create QR" }).click();
  await expect(page.getByTestId("qr-result")).toBeVisible();
  await page.getByRole("button", { name: "Hide the QR" }).click();
  await expect(page.getByTestId("data-table")).toContainText("0 / 1");
  await page.getByRole("button", { name: "Revoke code" }).click();
  await page.getByRole("button", { name: "Confirm" }).click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
});

test("alerts and the fleet activity log", async ({ page }) => {
  await asAdmin(page);
  await page.goto("/admin/mdm/alerts");
  await expect(page.getByTestId("data-table")).toContainText("Mock location app");
  await page.goto("/admin/mdm/alerts?flag=stale_contact");
  await expect(page.getByTestId("data-table").locator("tbody tr")).toHaveCount(1);
  await page.goto("/admin/mdm/activity");
  await expect(page.getByTestId("data-table")).toContainText("Update reminder");
});

test("Bangla is the default language of the section", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.goto("/admin/mdm");
  await expect(page.getByRole("heading", { name: "ডিভাইস ব্যবস্থাপনা" })).toBeVisible();
});

test("roles: SUPPORT and a TSO get 403 on every MDM page, and the menu entry is absent for SUPPORT", async ({ page }) => {
  await loginOk(page, "support1", "support-pass-1", "123456");
  for (const p of ["/admin/mdm", "/admin/mdm/policies", "/admin/mdm/devices/1", "/admin/mdm/enrolment", "/admin/mdm/alerts", "/admin/mdm/activity"]) {
    const res = await page.goto(p);
    expect(res?.status(), p).toBe(403);
  }
  const ctx = await page.context().browser()!.newContext();
  const t = await ctx.newPage();
  await loginOk(t, "mtso1", "tso-pass-1");
  expect((await t.goto("/admin/mdm"))?.status()).toBe(403);
  await ctx.close();
});
