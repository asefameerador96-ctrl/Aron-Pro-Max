import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock, hydrated } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

test("SKU form: the preview follows the pack size ('1 packet = 20 sticks' in Bangla), the pack label comes from the category", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/skus/new");
  await expect(page.getByTestId("sku-pack-pending")).toBeVisible();
  await hydrated(page.locator("#f-category_code")); // a click or fill before React attaches is lost or reverted (D-WEB-FIX-01)
  await page.locator("#f-category_code").selectOption("cigarette");
  await page.locator("#f-base_per_pack").fill("20");
  await expect(page.getByTestId("sku-pack-preview")).toHaveText("১ প্যাকেট = ২০ স্টিক");
  await expect(page.getByTestId("sku-pack-label")).toHaveText("প্যাকেট");
  await expect(page.getByTestId("sku-pack-warning")).toHaveCount(0);

  await page.locator("#f-base_per_pack").fill("15");
  await expect(page.getByTestId("sku-pack-warning")).toHaveCount(1);
  await page.locator("#f-category_code").selectOption("lighter");
  await page.locator("#f-base_unit").selectOption("piece");
  await page.locator("#f-base_per_pack").fill("25");
  await expect(page.getByTestId("sku-pack-preview")).toHaveText("১ বক্স = ২৫ পিস");
  await expect(page.getByTestId("sku-pack-label")).toHaveText("বক্স");
  await expect(page.getByTestId("sku-pack-warning")).toHaveCount(0);
});

test("SKU edit page shows the stored pack size and a pack-size edit is saved with a reason", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/skus/2");
  await expect(page.getByTestId("sku-pack-preview")).toHaveText("১ প্যাকেট = ২০ স্টিক");
  await hydrated(page.locator("#f-base_per_pack")); // a click or fill before React attaches is lost or reverted (D-WEB-FIX-01)
  await page.locator("#f-base_per_pack").fill("10");
  await expect(page.getByTestId("sku-pack-preview")).toHaveText("১ প্যাকেট = ১০ স্টিক");
  await page.locator("#reason").fill("Pack changed to ten sticks");
  await page.locator('form[data-testid="entity-form"] button[type="submit"]').click();
  // The save is a fetch after the click: poll the mock until the audit row lands (the read right after the click raced it under load).
  await expect.poll(async () => ((await (await fetch(`${MOCK}/__mock/state`)).json()) as { audit: unknown[] }).audit.at(-1)).toMatchObject({ entity: "sku", reason: "Pack changed to ten sticks" });
});
