import { expect, test } from "@playwright/test";
import writeExcelFile from "write-excel-file/node";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

const monthPlus = (n: number) => {
  const now = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Dhaka", year: "numeric", month: "2-digit" }).format(new Date());
  const d = new Date(Date.parse(`${now}-01T00:00:00Z`));
  d.setUTCMonth(d.getUTCMonth() + n);
  return d.toISOString().slice(0, 7);
};
const XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
const sheet = async (rows: (string | number)[][]) => ({ name: "targets.xlsx", mimeType: XLSX, buffer: await writeExcelFile([["route_code", "sku_code", "month", "quantity_base"], ...rows].map((r) => r.map((value) => ({ value })))).toBuffer() });
const stateOf = async () => (await fetch(`${MOCK}/__mock/state`)).json();

test("targets: upload, validation report, server preview, commit with a reason, listed, audited", async ({ page }) => {
  const m = monthPlus(1);
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/route-targets");
  await expect(page.getByTestId("rt-upload")).toBeVisible();

  // a sheet with local problems: nothing can be sent, the report lists row and column
  await page.getByTestId("rt-file").setInputFiles(await sheet([["R-334-01", "MaxR-20S", m, 100], ["", "MaxR-20S", m, 5], ["R-334-02", "MaxR-10S", m, "1.5"]]));
  await expect(page.getByTestId("rt-issues")).toBeVisible();
  await expect(page.getByTestId("rt-issues").locator("tbody tr")).toHaveCount(2);
  await expect(page.getByTestId("rt-check")).toHaveCount(0);

  // a sheet the server refuses (unknown SKU): the preview shows the sheet row
  await page.getByTestId("rt-file").setInputFiles(await sheet([["R-334-01", "MaxR-20S", m, 100], ["R-334-02", "NOPE", m, 5]]));
  await page.getByTestId("rt-check").click();
  await expect(page.getByTestId("rt-server-issues")).toContainText("৩"); // sheet row 3 in Bangla digits
  await expect(page.getByTestId("rt-commit")).toHaveCount(0);

  // a good sheet: preview, then commit
  await page.getByTestId("rt-file").setInputFiles(await sheet([["R-334-01", "MaxR-20S", m, 500], ["R-334-02", "MaxR-10S", m, 800]]));
  await page.getByTestId("rt-check").click();
  await expect(page.getByTestId("rt-summary")).toContainText("১,৩০০");
  await page.locator("#reason").fill("Monthly targets of the sales plan");
  await page.getByTestId("rt-commit").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  const s = await stateOf();
  expect(s.audit.at(-1)).toMatchObject({ entity: "route_target", reason: "Monthly targets of the sales plan" });
  expect(s.custom.routeTargets.filter((t: { month: string }) => t.month === m)).toHaveLength(2);

  await page.goto(`/admin/route-targets?month=${m}`);
  await expect(page.getByTestId("data-table")).toContainText("R-334-02");
  await expect(page.getByTestId("data-table")).toContainText("৮০০");
});

test("targets: a wrong file type and an unreadable workbook are refused with a message", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/route-targets");
  await page.getByTestId("rt-file").setInputFiles({ name: "t.csv", mimeType: "text/csv", buffer: Buffer.from("a,b") });
  await expect(page.getByTestId("rt-file-error")).toBeVisible();
  await page.getByTestId("rt-file").setInputFiles({ name: "t.xlsx", mimeType: XLSX, buffer: Buffer.from("not a zip") });
  await expect(page.getByTestId("rt-file-error")).toBeVisible();
});

test("targets: SUPPORT reads the list but has no upload", async ({ page }) => {
  await loginOk(page, "msupport1", "support-pass-1", "123456");
  await page.goto("/admin/route-targets");
  await expect(page.getByTestId("rt-upload")).toHaveCount(0);
  await expect(page.getByTestId("data-table")).toContainText("R-334-01");
});

test("targets: the template downloads as a workbook", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/route-targets");
  const [download] = await Promise.all([page.waitForEvent("download"), page.getByTestId("rt-template").click()]);
  expect(download.suggestedFilename()).toBe("route-targets-template.xlsx");
});

test("calendar: working days of the coming three months are listed", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/calendar");
  await expect(page.getByTestId("working-days").locator("tbody tr")).toHaveCount(3);
});
