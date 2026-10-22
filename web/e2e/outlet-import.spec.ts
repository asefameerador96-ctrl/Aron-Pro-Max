import { expect, test } from "@playwright/test";
import writeExcelFile from "write-excel-file/node";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

const XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
const HEAD = ["name", "owner_name", "zone_code", "cluster", "channel", "outlet_kind"];
const sheet = async (rows: string[][]) => ({ name: "outlets.xlsx", mimeType: XLSX, buffer: await writeExcelFile([HEAD, ...rows].map((r) => r.map((value) => ({ value })))).toBuffer() });

test("outlet form: the channel is required, it cannot be left empty", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/outlets/new");
  await expect(page.locator("#f-channel")).toHaveAttribute("aria-required", "true");
  await expect(page.locator("#f-channel")).toHaveValue("");
  await page.locator("#f-name").fill("Channel Test Store");
  await page.locator("#f-owner_name").fill("Owner Name");
  await page.locator("#f-zone_id").selectOption("14");
  await page.locator("#f-cluster_id").selectOption("1");
  await page.locator("#f-outlet_kind").selectOption("retail");
  await page.locator("#reason").fill("Testing the mandatory channel");
  await page.getByTestId("entity-form").locator('button[type="submit"]').click();
  await expect(page.getByTestId("form-ok")).toHaveCount(0);
  await page.locator("#f-channel").selectOption("MT");
  await page.getByTestId("entity-form").locator('button[type="submit"]').click();
  await page.waitForURL(/\/admin\/outlets$/); // a created outlet returns to the list
  const s = await (await fetch(`${MOCK}/__mock/state`)).json();
  expect(s.tables.outlets.at(-1)).toMatchObject({ name: "Channel Test Store", channel: "MT" });
});

test("outlet import: a row without a channel is refused in the report; a good sheet is previewed and committed", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/outlet-import");
  await page.getByTestId("oi-file").setInputFiles(await sheet([["Store A", "Owner A", "Z-334-1", "Banani Market", "", "retail"], ["Store B", "Owner B", "Z-334-1", "Banani Market", "Wholesale", "retail"]]));
  await expect(page.getByTestId("oi-issues").locator("tbody tr")).toHaveCount(2);
  await expect(page.getByTestId("oi-check")).toHaveCount(0);

  await page.getByTestId("oi-file").setInputFiles(await sheet([["Store A", "Owner A", "Z-334-1", "Banani Market", "DCC", "retail"], ["Store B", "Owner B", "Z-334-1", "Gulshan-1 Circle", "Astha", "wholesale"]]));
  await page.getByTestId("oi-check").click();
  await expect(page.getByTestId("oi-summary")).toContainText("DCC");
  await page.locator("#reason").fill("Gulshan outlet census import");
  await page.getByTestId("oi-commit").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  const s = await (await fetch(`${MOCK}/__mock/state`)).json();
  expect(s.audit.at(-1)).toMatchObject({ entity: "outlet_import", reason: "Gulshan outlet census import" });
  expect(s.tables.outlets.filter((o: { name: string }) => o.name.startsWith("Store "))).toHaveLength(2);
});

test("outlet import: SUPPORT has no import", async ({ page }) => {
  await loginOk(page, "msupport1", "support-pass-1", "123456");
  await page.goto("/admin/outlet-import");
  await expect(page.getByTestId("oi-upload")).toHaveCount(0);
});
