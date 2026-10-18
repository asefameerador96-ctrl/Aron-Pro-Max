// listAudit per role (docs/24 s8.5, backend AuditRead.kt): ANALYST, SUPPORT, ADMIN and SUPERADMIN read the log nationally,
// everyone else gets 403. Personal data is masked for everyone; ANALYST and SUPPORT also see the reason masked. No "not available yet" note.
import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

const REASON = "Left the company in October";

test.beforeEach(async () => {
  await resetMock();
  const row = { as: "admin1", entity: "user", entity_id: "42", action: "user.update", before: { status: "active", full_name: "Rahim Uddin" }, after: { status: "disabled", full_name: "Rahim U." }, reason: REASON };
  const res = await fetch(`${MOCK}/__mock/audit`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(row) });
  expect(res.status).toBe(204);
});

test("ADMIN reads the row with its reason; the personal name stays masked", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  const res = await page.goto("/admin/audit?entity=user");
  expect(res?.status()).toBe(200);
  const table = page.getByTestId("data-table");
  await expect(table).toContainText(REASON);
  await expect(table).toContainText("disabled");
  await expect(table).not.toContainText("Rahim"); // both sides arrive masked, so the line reads as unchanged for ADMIN
});

for (const [user, pass, totp] of [["analyst1", "analyst-pass-1", undefined], ["support1", "support-pass-1", "123456"]] as const) {
  test(`${user} reads the row with allowlisted values only and the reason masked`, async ({ page }) => {
    await loginOk(page, user, pass, totp);
    const res = await page.goto("/admin/audit?entity=user");
    expect(res?.status()).toBe(200);
    const table = page.getByTestId("data-table");
    await expect(table).toContainText("disabled"); // `status` is on the user allowlist
    await expect(table).not.toContainText(REASON);
    await expect(table).not.toContainText("Rahim");
    await expect(table).toContainText("[masked]");
  });
}

test("ANALYST finds the audit log in the menu; DMO and TSO are refused", async ({ page }) => {
  await loginOk(page, "analyst1", "analyst-pass-1");
  await expect(page.locator('a[href="/admin/audit"]').first()).toBeAttached();
  await page.context().clearCookies();
  for (const [user, pass] of [["dmo1", "dmo-pass-1"], ["tso334", "tso-pass-1"]] as const) {
    await loginOk(page, user, pass);
    const res = await page.goto("/admin/audit");
    expect(res?.status(), user).toBe(403);
    await page.context().clearCookies();
  }
});
