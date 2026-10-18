// Completion pass item 8: print fits A4 portrait without clipped columns or the app shell; the CSV amount equals the on-screen amount to the paisa.
import { readFile } from "node:fs/promises";
import { expect, test } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

const A4_PORTRAIT_PX = { width: 794, height: 1123 }; // 210 x 297 mm at 96 dpi

for (const path of ["/", "/drill/calls", "/drill/category?code=cigarette", "/drill/channel", "/drill/login"]) {
  test(`print ${path}: A4 portrait, no clipped column, no sidebar or top bar`, async ({ page }) => {
    await loginOk(page, "tso334", "tso-pass-1");
    await page.getByRole("link", { name: "English" }).click();
    await page.goto(path);
    await expect(page.locator("h1").first()).toBeVisible();
    await page.setViewportSize(A4_PORTRAIT_PX);
    await page.emulateMedia({ media: "print" });
    await expect(page.getByTestId("sidebar")).toBeHidden();
    await expect(page.getByTestId("top-bar")).toBeHidden();
    const clipped = await page.evaluate(() =>
      [...document.querySelectorAll("table")].flatMap((tb) => {
        const r = tb.getBoundingClientRect();
        return r.right > window.innerWidth + 1 || r.left < -1 ? [`${tb.getAttribute("data-testid")} spans ${Math.round(r.left)}..${Math.round(r.right)} of ${window.innerWidth}`] : [];
      }),
    );
    expect(clipped).toEqual([]);
    const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(over).toBeLessThanOrEqual(1);
    const pdf = await page.pdf({ format: "A4", printBackground: false });
    expect(pdf.byteLength).toBeGreaterThan(1000);
  });
}

test("CSV amount equals the on-screen amount to the paisa; Bangla export is UTF-8 with a BOM", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await page.getByRole("link", { name: "English" }).click();
  await page.goto("/drill/category?code=cigarette");
  const rows = page.getByTestId("rows-cat-routes").locator("tbody tr");
  const screen: string[] = [];
  for (const tr of await rows.all()) screen.push((await tr.locator("td").last().innerText()).replace(/,/g, ""));
  const [dl] = await Promise.all([page.waitForEvent("download"), page.getByTestId("csv-category-routes").click()]);
  const bytes = await readFile((await dl.path())!);
  expect([...bytes.subarray(0, 3)]).toEqual([0xef, 0xbb, 0xbf]);
  const lines = bytes.toString("utf8").replace(/^﻿/, "").trim().split("\r\n").slice(1);
  expect(lines).toHaveLength(screen.length);
  lines.forEach((line, i) => {
    const csvTaka = line.split(",").at(-1)!; // three decimals
    const cents = Math.round(Number(csvTaka) * 100); // milli-taka to paisa, half up
    expect((cents / 100).toFixed(2)).toBe(screen[i]);
  });
  // Bangla: same file, Bengali digits and header
  await page.getByRole("link", { name: "বাংলা" }).click();
  await page.goto("/drill/category?code=cigarette");
  const [dl2] = await Promise.all([page.waitForEvent("download"), page.getByTestId("csv-category-routes").click()]);
  const text = (await readFile((await dl2.path())!)).toString("utf8");
  expect(text).toMatch(/রুট/);
  expect(text.split("\r\n").slice(1).join("")).not.toMatch(/[0-9]/);
});
