import { expect, test } from "@playwright/test";
import { MOCK, dhakaPlus, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});
const state = async () => (await fetch(`${MOCK}/__mock/state`)).json();

test("a survey edit starts from the stored keys, flags and options, and saves unchanged", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/surveys");
  await page.getByTestId("edit-1").click();
  await expect(page.getByTestId("key-0")).toHaveValue("posm_displayed");
  await page.locator("#reason").fill("Re-publishing with no change");
  await page.getByTestId("def-submit").click();
  await expect(page.getByTestId("def-message")).toBeVisible();
  const s = await state();
  const row = s.tables.surveys.find((r: { survey_id: number }) => r.survey_id === 1);
  expect(row.questions[0]).toMatchObject({ key: "posm_displayed", required: true, photo: true });
});

test("a content edit keeps the stored file when none is chosen", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/content");
  await page.getByTestId("edit-1").click();
  await page.locator("#reason").fill("Extending the film by a week");
  await page.getByTestId("def-submit").click();
  // the mock refuses an asset it never received: the stored one is sent, so the form must not block on a missing file
  await expect(page.getByTestId("error-f-file")).toHaveCount(0);
});

test("the acting scope end date is sent per node (exclusive end)", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/user-scope/2001");
  const end = dhakaPlus(8);
  await page.getByTestId("until-territory-6").fill(end);
  await page.locator("#reason").fill("Acting cover until the 8th");
  await page.getByTestId("scope-save").click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  expect((await state()).custom.scopes["2001"].nodes[0]).toMatchObject({ node_type: "territory", node_id: 6, valid_to: end });
});

test("an end date not after the start is refused before the call", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/user-scope/2001");
  await page.getByTestId("until-territory-6").fill(dhakaPlus(0));
  await page.locator("#reason").fill("Wrong end date on purpose");
  await page.getByTestId("scope-save").click();
  await expect(page.getByText(/শেষ তারিখ শুরুর/)).toBeVisible();
});

test("feedback is filtered by category and status", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/feedback?category_code=suggestion"); // tso-manual W1 (FB-03): the categories are suggestion and feedback
  await expect(page.getByTestId("data-table")).toContainText("Route list missing an outlet");
  await expect(page.getByTestId("data-table")).not.toContainText("Memo print is slow");
  await page.goto("/admin/feedback?status=new");
  await expect(page.getByTestId("data-table")).toContainText("Memo print is slow");
  await expect(page.getByTestId("data-table")).not.toContainText("Route list missing an outlet");
});

test("the outlet sub-channel is a select with the code-list labels", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/outlets/1");
  await expect(page.locator("#f-sub_channel_id")).toHaveValue("11");
  await expect(page.locator("#f-sub_channel_id option", { hasText: "Tea stall" })).toHaveCount(1);
});
