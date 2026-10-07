import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});
const state = async () => (await fetch(`${MOCK}/__mock/state`)).json();

test("F-ADM-020: a survey with a conditional question is created with a reason", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/surveys");
  await expect(page.getByTestId("data-table")).toContainText("POSM check");
  await page.getByTestId("def-new").click();
  await page.locator("#f-title_en").fill("Shelf survey");
  await page.locator("#f-valid_from").fill("2026-11-01");
  await page.getByTestId("label-0").fill("Is the shelf stocked?");
  await page.getByTestId("def-add-row").click();
  await page.getByTestId("label-1").fill("Why not?");
  await page.getByRole("combobox", { name: /Show only if|শুধু তখন/ }).nth(1).selectOption("q1");
  await page.locator("#reason").fill("New shelf survey for Q4");
  await page.getByTestId("def-submit").click();
  await expect(page.getByTestId("data-table")).toContainText("Shelf survey");
  const s = await state();
  const row = s.tables.surveys.find((r: { title_en: string }) => r.title_en === "Shelf survey");
  expect(row.questions).toHaveLength(2);
  expect(s.audit.at(-1)).toMatchObject({ entity: "surveys", reason: "New shelf survey for Q4" });
});

test("a duplicate question key is refused in the form", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/surveys");
  await page.getByTestId("def-new").click();
  await page.locator("#f-title_en").fill("Dup");
  await page.locator("#f-valid_from").fill("2026-11-01");
  await page.getByTestId("label-0").fill("One");
  await page.getByTestId("def-add-row").click();
  await page.getByTestId("label-1").fill("Two");
  await page.getByTestId("key-1").fill("q1");
  await page.locator("#reason").fill("Duplicate key attempt");
  await page.getByTestId("def-submit").click();
  await expect(page.getByTestId("error-rows")).toBeVisible();
});

test("content: a KV image is uploaded and the item is created for a zone", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/content");
  await page.getByTestId("def-new").click();
  await page.locator("#f-kind").selectOption("kv");
  await page.locator("#f-title_en").fill("Poster");
  await page.locator("#f-valid_from").fill("2026-11-01");
  await page.locator("#f-valid_to").fill("2026-12-01");
  await page.locator("#f-file").setInputFiles({ name: "p.png", mimeType: "image/png", buffer: Buffer.from("png-bytes") });
  await page.getByRole("button", { name: /Add a place|স্থান যোগ/ }).click();
  await page.getByRole("textbox", { name: /^Id$|^আইডি$/ }).fill("14");
  await page.locator("#reason").fill("Q4 poster for Banani");
  await page.getByTestId("def-submit").click();
  await expect(page.getByTestId("data-table")).toContainText("Poster");
  const s = await state();
  expect(s.tables.content.find((r: { title_en: string }) => r.title_en === "Poster").assigned_scope).toEqual([{ node_type: "zone", node_id: 14 }]);
});
