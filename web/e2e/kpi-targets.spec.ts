import { expect, test } from "@playwright/test";
import writeExcelFile from "write-excel-file/node";
import { MOCK, loginOk, resetMock, hydrated } from "./helpers";

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
const sheet = async (header: string[], rows: (string | number)[][]) => ({ name: "kpi.xlsx", mimeType: XLSX, buffer: await writeExcelFile([header, ...rows].map((r) => r.map((value) => ({ value })))).toBuffer() });
const BRAND = ["route_code", "brand_code", "month", "memo_target", "kpi_alloc_taka"];
const stateOf = async () => (await fetch(`${MOCK}/__mock/state`)).json();

test("kpi targets: five sections, local report, server preview, commit with a reason, then read back", async ({ page }) => {
  const m = monthPlus(1);
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/kpi-targets");
  await expect(page.getByTestId("kt-upload")).toBeVisible();
  for (const k of ["brand", "month", "programme", "campaign", "campaign-sku"]) await expect(page.getByTestId(`kt-kind-${k}`)).toBeVisible();

  // local problems: nothing is sent, the report lists row and column
  await page.getByTestId("kt-file").setInputFiles(await sheet(BRAND, [["R-334-01", "MAXR", m, 119, 5700], ["", "MAXR", m, 5, 1], ["R-334-01", "VAL", m, "1.5", 1]]));
  await expect(page.getByTestId("kt-issues").locator("tbody tr")).toHaveCount(2);
  await expect(page.getByTestId("kt-check")).toHaveCount(0);

  // a sheet the server refuses (unknown brand): the preview names the sheet row, no commit button
  await page.getByTestId("kt-file").setInputFiles(await sheet(BRAND, [["R-334-01", "MAXR", m, 119, 5700], ["R-334-01", "ZZZ", m, 5, 1]]));
  await page.getByTestId("kt-check").click();
  await expect(page.getByTestId("kt-server-issues")).toContainText("৩");
  await expect(page.getByTestId("kt-commit")).toHaveCount(0);

  // a good sheet: preview, then commit
  await page.getByTestId("kt-file").setInputFiles(await sheet(BRAND, [["R-334-01", "MAXR", m, 119, 5700.5], ["R-334-01", "VAL", m, 146, ""]]));
  await page.getByTestId("kt-check").click();
  await expect(page.getByTestId("kt-summary")).toContainText("২");
  await page.locator("#reason").fill("KPI targets of the sales plan");
  await page.getByTestId("kt-commit").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  const s = await stateOf();
  expect(s.audit.at(-1)).toMatchObject({ entity: "route_kpi_target", reason: "KPI targets of the sales plan" });
  expect(s.custom.kpi.brand).toHaveLength(2);
  expect(s.custom.kpi.brand[0]).toMatchObject({ kpi_alloc_mtk: 5_700_500 }); // taka in the sheet, integer milli-taka stored

  // the read panel shows what is stored for the route and month
  await page.goto(`/admin/kpi-targets?route_code=R-334-01&month=${m}`);
  await expect(page.getByTestId("kt-stored")).toContainText("Max Royal");
  await expect(page.getByTestId("kt-stored")).toContainText("VAL");
});

test("kpi targets: each of the other sections takes a sheet through preview and commit", async ({ page }) => {
  const m = monthPlus(1);
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  const cases: { kind: string; header: string[]; row: (string | number)[]; member: string }[] = [
    { kind: "month", header: ["route_code", "month", "kpi_alloc_taka", "cpr_target_bp", "cpr_alloc_taka"], row: ["R-334-01", m, 5700, 2700, 1500], member: "month" },
    { kind: "programme", header: ["route_code", "programme_code", "month", "target_qty"], row: ["R-334-01", "MBLS", m, "20.5"], member: "programme" },
    { kind: "campaign", header: ["outlet_code", "programme_code", "month", "std_target", "memo_target"], row: ["DHK-334-001", "CAMP-NOV", m, 140, 8], member: "campaign" },
    { kind: "campaign-sku", header: ["outlet_code", "programme_code", "month", "sku_code", "qty_base"], row: ["DHK-334-001", "CAMP-NOV", m, "MaxR-20S", 140], member: "campaignSku" },
  ];
  for (const c of cases) {
    await page.goto(`/admin/kpi-targets?kind=${c.kind}`);
    await hydrated(page.getByTestId("kt-file"));
    await page.getByTestId("kt-file").setInputFiles(await sheet(c.header, [c.row]));
    await page.getByTestId("kt-check").click();
    await expect(page.getByTestId("kt-summary")).toBeVisible();
    await page.locator("#reason").fill("KPI targets of the sales plan");
    await page.getByTestId("kt-commit").click();
    await expect(page.getByTestId("form-ok")).toBeVisible();
    expect((await stateOf()).custom.kpi[c.member]).toHaveLength(1);
  }
  // the read panel lists the outlet campaign rows of the route too (DHK-334-001 belongs to R-334-01)
  await page.goto(`/admin/kpi-targets?route_code=R-334-01&month=${m}`);
  await expect(page.getByTestId("kt-stored")).toContainText("DHK-334-001");
  await expect(page.getByTestId("kt-stored")).toContainText("MaxR-20S");
});

test("kpi targets: a wrong file type is refused, the template downloads, an unknown route reads as empty", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/kpi-targets?kind=programme");
  await hydrated(page.getByTestId("kt-file"));
  await page.getByTestId("kt-file").setInputFiles({ name: "t.csv", mimeType: "text/csv", buffer: Buffer.from("a,b") });
  await expect(page.getByTestId("kt-file-error")).toBeVisible();
  const [download] = await Promise.all([page.waitForEvent("download"), page.getByTestId("kt-template").click()]);
  expect(download.suggestedFilename()).toBe("kpi-targets-programme-template.xlsx");
  await page.goto("/admin/kpi-targets?route_code=R-334-01");
  await expect(page.getByTestId("kt-stored-empty")).toBeVisible();
});

test("kpi targets: SUPPORT reads but has no upload", async ({ page }) => {
  await loginOk(page, "msupport1", "support-pass-1", "123456");
  await page.goto("/admin/kpi-targets");
  await expect(page.getByTestId("kt-upload")).toHaveCount(0);
  await expect(page.getByTestId("kt-read-only")).toBeVisible();
  await page.goto("/admin/kpi-targets?route_code=R-334-01");
  await expect(page.getByTestId("kt-stored-empty")).toBeVisible();
});

test("kpi targets: when the API fails the read panel shows a plain error, never a raw code", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await fetch(`${MOCK}/__mock/fail`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ prefix: "/v1/admin/route-kpi-targets" }) });
  try {
    await page.goto("/admin/kpi-targets?route_code=R-334-01");
    const body = (await page.locator("body").innerText()).trim();
    expect(body.length).toBeGreaterThan(20);
    expect(body).not.toMatch(/ERR_[A-Z_]{3,}/);
  } finally {
    await fetch(`${MOCK}/__mock/fail`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ prefix: null }) });
  }
});
