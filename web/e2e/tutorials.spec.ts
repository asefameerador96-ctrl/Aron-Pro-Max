import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

test("F-ADM-026: a PDF manual is uploaded to the SAS URL and listed, with an audit row", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/tutorials");
  await expect(page.getByTestId("data-table")).toContainText("Taking a first order");
  await page.locator("#f-kind").selectOption("manual");
  await page.locator("#f-title_en").fill("TSO manual");
  await page.getByTestId("role-TSO").check();
  await page.locator("#f-file").setInputFiles({ name: "m.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.4 test") });
  await page.locator("#reason").fill("Adding the TSO manual");
  await page.getByTestId("tutorial-submit").click();
  await expect(page.getByTestId("data-table")).toContainText("TSO manual");
  const s = await (await fetch(`${MOCK}/__mock/state`)).json();
  expect(s.audit.at(-1)).toMatchObject({ entity: "tutorial", reason: "Adding the TSO manual" });
});

test("a wrong file type is refused before any upload", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/tutorials");
  await page.locator("#f-title_en").fill("Bad file");
  await page.getByTestId("role-SR").check();
  await page.locator("#f-file").setInputFiles({ name: "m.txt", mimeType: "text/plain", buffer: Buffer.from("x") });
  await page.locator("#reason").fill("Trying a text file");
  await page.getByTestId("tutorial-submit").click();
  await expect(page.getByTestId("error-f-file")).toBeVisible();
});
