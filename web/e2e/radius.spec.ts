import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

test("F-TSO-025: a TSO proposes a radius for its own territory and sees that it waits for approval", async ({ page }) => {
  await loginOk(page, "mtso1", "tso-pass-1");
  await page.goto("/admin/radius");
  await page.locator("#f-radius").fill("120");
  await page.locator("#reason").fill("Dense market near the station");
  await page.getByTestId("radius-submit").click();
  await expect(page.getByTestId("radius-message")).toContainText("অনুমোদনের অপেক্ষায়");
  const s = await (await fetch(`${MOCK}/__mock/state`)).json();
  expect(s.audit.at(-1)).toMatchObject({ action: "config.propose", reason: "Dense market near the station" });
});
