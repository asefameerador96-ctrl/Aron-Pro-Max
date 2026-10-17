import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

// 1x1 PNG
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", "base64");

test.beforeEach(async () => {
  await resetMock();
});

test("F-ADM-065: a pack image is uploaded, bound to the SKU with a reason, shown, then removed", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/skus/1");
  await page.locator("#f-image_asset_id").setInputFiles({ name: "pack.png", mimeType: "image/png", buffer: PNG });
  await expect(page.getByTestId("asset-image_asset_id")).toContainText("pack.png");
  await page.locator("#reason").fill("Adding the new pack photo");
  await page.getByTestId("entity-form").locator('button[type="submit"]').click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  const s = await (await fetch(`${MOCK}/__mock/state`)).json();
  expect(s.audit.at(-1)).toMatchObject({ entity: "sku", reason: "Adding the new pack photo" });

  await page.goto("/admin/skus/1");
  await expect(page.getByTestId("asset-image_asset_id").locator("img")).toHaveAttribute("src", /blob\.example\/sku\//);
  await page.getByTestId("asset-image_asset_id").getByRole("button", { name: "সরান" }).click();
  await page.locator("#reason").fill("Removing the outdated photo");
  await page.getByTestId("entity-form").locator('button[type="submit"]').click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  await page.goto("/admin/skus/1");
  await expect(page.getByTestId("asset-image_asset_id").locator("img")).toHaveCount(0);
});

test("a wrong type or an oversize image is refused before any upload", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/skus/1");
  await page.locator("#f-image_asset_id").setInputFiles({ name: "a.gif", mimeType: "image/gif", buffer: Buffer.from("GIF89a") });
  await expect(page.getByTestId("error-f-image_asset_id")).toBeVisible();
  await page.locator("#f-image_asset_id").setInputFiles({ name: "big.png", mimeType: "image/png", buffer: Buffer.alloc(307_201, 1) });
  await expect(page.getByTestId("error-f-image_asset_id")).toBeVisible();
  await expect(page.getByTestId("asset-image_asset_id")).not.toContainText("big.png");
});

test("SUPPORT cannot edit a SKU image", async ({ page }) => {
  await loginOk(page, "msupport1", "support-pass-1", "123456");
  await page.goto("/admin/skus/1");
  await expect(page.locator("#f-image_asset_id")).toHaveCount(0);
});
