import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

test("F-ADM-028: the feedback inbox lists feedback and sets a status with a reason", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/feedback");
  await expect(page.getByTestId("data-table")).toContainText("Memo print is slow");
  await page.getByRole("row", { name: /Memo print is slow/ }).getByTestId("action-status").click();
  await page.locator("#f-status").selectOption("resolved");
  await page.locator("#reason").fill("Checked with the field team");
  await page.getByRole("button", { name: "অবস্থা ঠিক করুন" }).last().click();
  await page.waitForURL(/\/admin\/feedback$/);
  const s = await (await fetch(`${MOCK}/__mock/state`)).json();
  expect(s.tables.feedback.find((r: { feedback_uuid: string }) => r.feedback_uuid.startsWith("aaaa"))).toMatchObject({ status: "resolved" });
});
