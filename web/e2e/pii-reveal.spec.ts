// PII reveal per role (backend-authz V0086, POST /v1/auth/reauth): ADMIN steps up with password + authenticator code and sees a
// countdown from Me.pii_until; the page re-masks when it ends; a wrong code, a lockout and ANALYST/SUPPORT (no pii.reveal) are covered.
// D-AUDIT-PII-01: "[redacted]" audit values show as a quiet chip.
import { expect, test, type Page } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

const post = (path: string, body: unknown) => fetch(`${MOCK}${path}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

async function reveal(page: Page, code: string): Promise<void> {
  await page.getByTestId("pii-open").click();
  await page.getByTestId("pii-password").fill("admin-pass-1");
  await page.getByTestId("pii-code").fill(code);
  await page.getByTestId("pii-submit").click();
}

test.beforeEach(async () => {
  await resetMock();
});

test("ADMIN reveals with password and code, sees the countdown, and the page re-masks when the window ends", async ({ page }) => {
  await post("/__mock/pii-window", { seconds: 4 });
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.getByRole("link", { name: "English" }).click();
  await page.goto("/admin/audit");
  await reveal(page, "123456");
  await expect(page.getByTestId("pii-active")).toContainText(/Personal data shown for 0:0\d more/);
  await expect(page.getByTestId("pii-open")).toBeVisible({ timeout: 10_000 }); // window over: masked again, button back
});

test("a wrong code says so; three wrong tries lock the reveal", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.getByRole("link", { name: "English" }).click();
  await page.goto("/admin/audit");
  await reveal(page, "000000");
  await expect(page.getByTestId("pii-error")).toHaveText("The password or code is not right.");
  for (let i = 0; i < 2; i++) {
    await page.getByTestId("pii-submit").click();
    await expect(page.getByTestId("pii-error")).toBeVisible();
  }
  await page.getByTestId("pii-submit").click();
  await expect(page.getByTestId("pii-error")).toContainText("Too many tries");
});

for (const [user, pass, totp] of [["analyst1", "analyst-pass-1", undefined], ["support1", "support-pass-1", "123456"]] as const) {
  test(`${user} has no Show personal data control`, async ({ page }) => {
    await loginOk(page, user, pass, totp);
    await page.goto("/admin/audit");
    await expect(page.locator("h1").first()).toBeVisible();
    await expect(page.getByTestId("pii-open")).toHaveCount(0);
  });
}

test("a value redacted at write shows as a quiet chip to a revealed ADMIN", async ({ page }) => {
  const row = { as: "admin1", entity: "outlet", entity_id: "7", action: "outlet.update", before: { owner_name: "Rahim", status: "active" }, after: { owner_name: "[redacted]", status: "closed" }, reason: null };
  expect((await post("/__mock/audit", row)).status).toBe(204);
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.goto("/admin/audit?entity=outlet");
  await reveal(page, "123456");
  await expect(page.getByTestId("audit-redacted").first()).toHaveAttribute("title", /./);
});
