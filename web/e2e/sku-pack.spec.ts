import { expect, test } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

test("SKU form: the preview follows the pack size ('1 packet = 20 sticks' in Bangla), the pack label comes from the category", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/skus/new");
  await expect(page.getByTestId("sku-pack-pending")).toBeVisible();
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

test("SKU edit page shows the stored pack size and says it is fixed", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/skus/2");
  await expect(page.getByTestId("sku-pack-preview")).toHaveText("১ প্যাকেট = ২০ স্টিক");
  await expect(page.getByTestId("sku-pack")).toContainText("স্থির");
});
