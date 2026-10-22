// Completion pass: role extras on the home, drill-downs from the cards (scope and date kept in the URL), CSV export and the
// refresh rule (no faster than 60 s, nothing while the tab is hidden).
import { expect, test, type Page } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

async function english(page: Page) {
  await page.getByRole("link", { name: "English" }).click();
  await page.goto("/overview");
}

test("TSO home has the SR table, DMO home the ranking table", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await expect(page.getByTestId("table-sr")).toBeVisible();
  await expect(page.getByTestId("table-rank")).toHaveCount(0);
  await page.context().clearCookies();
  await loginOk(page, "dmo1", "dmo-pass-1");
  await english(page);
  await expect(page.getByTestId("table-rank")).toBeVisible();
  await expect(page.getByTestId("table-sr")).toHaveCount(0);
});

test("category card opens its drill-down; CSV carries the shown rows with the unit", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await page.getByTestId("drill-cat-cigarette").click();
  await expect(page).toHaveURL(/\/drill\/category\?code=cigarette/);
  await expect(page.getByTestId("table-cat-routes")).toBeVisible();
  await expect(page.getByTestId("table-cat-routes").getByText("Stick").first()).toBeVisible();
  const [dl] = await Promise.all([page.waitForEvent("download"), page.getByTestId("csv-category-routes").click()]);
  expect(dl.suggestedFilename()).toBe("category-routes.csv");
  const text = await (await import("node:fs/promises")).readFile((await dl.path())!, "utf8");
  expect(text.startsWith("﻿")).toBe(true);
  expect(text.split("\r\n")[0]).toContain("Route");
  await page.getByTestId("drill-back").click();
  await expect(page.getByTestId("dashboard-tiles")).toBeVisible();
});

test("channel slice, CPR, strike and login cards each open their drill-down", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  await page.getByTestId("slice-link-grocery").first().click();
  await expect(page).toHaveURL(/\/drill\/channel\?code=grocery/);
  await expect(page.getByTestId("table-channels")).toBeVisible();
  for (const [id, table] of [["drill-calls", "table-calls"], ["drill-strike", "table-calls"], ["drill-login", "table-not-logged-in"]] as const) {
    await page.goto("/overview");
    await page.getByTestId(id).click();
    await expect(page.getByTestId(table)).toBeVisible();
  }
});

test("scope and date stay in the drill-down URL and back link", async ({ page }) => {
  await loginOk(page, "dmo1", "dmo-pass-1");
  await english(page);
  const res = await page.goto("/drill/calls?date=2026-10-01");
  expect(res?.status()).toBe(200);
  await expect(page.getByTestId("drill-back")).toHaveAttribute("href", /date=2026-10-01/);
});

test("an unknown drill-down shows the not-found page", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await page.goto("/drill/nope");
  await expect(page.getByTestId("page-not-found")).toBeVisible();
});

test("auto-refresh does not poll faster than 60 s", async ({ page }) => {
  await page.clock.install();
  await loginOk(page, "tso334", "tso-pass-1");
  await english(page);
  let reads = 0;
  page.on("request", (r) => {
    if (r.headers()["rsc"] === "1" && new URL(r.url()).pathname === "/") reads++;
  });
  await page.clock.runFor(30_000);
  expect(reads).toBe(0);
  await page.clock.runFor(35_000);
  await expect.poll(() => reads).toBeGreaterThan(0);
});
