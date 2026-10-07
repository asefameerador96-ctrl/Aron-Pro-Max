import { expect, test } from "@playwright/test";
import { MOCK, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

const stateOf = async () => (await fetch(`${MOCK}/__mock/state`)).json();

test("routes: day mask checkboxes, label kept apart from the name, future effective date, audited", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.goto("/admin/routes");
  await expect(page.getByTestId("data-table")).toContainText("Banani 3F");
  await expect(page.getByTestId("data-table")).toContainText("শনি সোম বুধ"); // mask 21 = Sat, Mon, Wed

  await page.getByRole("row", { name: /Banani 3F/ }).getByRole("link", { name: "সম্পাদনা" }).click();
  await expect(page.locator("#f-name")).toHaveValue("Banani 3F");
  await page.getByLabel("রবি").check(); // + Sun
  await page.getByLabel("শনি").uncheck(); // - Sat
  await page.locator("#f-display_label").fill("(Sun, Mon, Wed)");
  await page.locator("#f-effective_from").fill("2026-10-25");
  await page.locator("#reason").fill("Visit days moved after the van roster change");
  await page.getByRole("button", { name: "সংরক্ষণ" }).click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  const last = (await stateOf()).audit.at(-1);
  expect(last).toMatchObject({ entity: "route", reason: "Visit days moved after the van roster change" });
  expect(last.after).toMatchObject({ visit_days_mask: 22, display_label: "(Sun, Mon, Wed)" });
});

test("assignments: overlap is refused, a cover is accepted, an open one can be ended; no edit link", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.goto("/admin/route-assignments");
  await expect(page.getByTestId("data-table")).toContainText("R-334-01 · Banani Daily");
  await expect(page.getByTestId("data-table")).toContainText("sr334001 · Testing Banani");
  await expect(page.getByRole("link", { name: "সম্পাদনা" })).toHaveCount(0);

  await page.getByTestId("create-link").click();
  await page.locator("#f-route_id").selectOption({ label: "R-334-01 · Banani Daily" });
  await page.locator("#f-user_id").selectOption({ label: "sr334002 · Karim Mia" });
  await page.locator("#f-kind").selectOption("primary");
  await page.locator("#f-valid_from").fill("2026-10-10");
  await page.locator("#reason").fill("Second SR on the same route by mistake");
  await page.getByRole("button", { name: "তৈরি করুন" }).click();
  await expect(page.getByTestId("form-error")).toHaveText("একই রুট ও তারিখে আগের একটি দায়িত্বের সাথে এটি মিলে যাচ্ছে।");

  await page.locator("#f-kind").selectOption("cover");
  await page.getByRole("button", { name: "তৈরি করুন" }).click();
  await expect(page.getByTestId("data-table")).toContainText("বদলি");

  // end the first (open) assignment
  await page.getByRole("row", { name: /sr334001/ }).getByTestId("action-end").click();
  await expect(page.getByTestId("row-summary")).toContainText("sr334001");
  await page.locator("#f-valid_to").fill("2026-10-20");
  await page.locator("#reason").fill("SR moved to another route");
  await page.getByRole("button", { name: "দায়িত্ব শেষ করুন" }).click();
  await expect(page.getByTestId("data-table")).toContainText("2026-10-20");
  const s = await stateOf();
  expect(s.audit.some((a: { entity: string; reason: string }) => a.entity === "route_assignment" && a.reason === "SR moved to another route")).toBe(true);
});

test("users: create shows the temporary password once; reset by support; disable keeps the row", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.goto("/admin/users/new");
  await page.locator("#f-username").fill("sr999001");
  await page.locator("#f-full_name").fill("New Field Rep");
  await page.locator("#f-role").selectOption("SR");
  await page.locator("#f-phone").fill("০১৭১২৩৪৫৬৭৮"); // Bengali digits are accepted
  await page.locator("#reason").fill("Joining the Banani team this week");
  await page.getByRole("button", { name: "তৈরি করুন" }).click();
  await expect(page.getByTestId("shown-once")).toBeVisible();
  await expect(page.getByTestId("once-temporary_password")).toContainText("Tmp-");
  await page.getByRole("link", { name: "সম্পন্ন" }).click();
  await expect(page.getByTestId("data-table")).toContainText("sr999001");

  // disable
  await page.getByRole("row", { name: /sr999001/ }).getByRole("link", { name: "সম্পাদনা" }).click();
  await page.locator("#f-status").selectOption("disabled");
  await page.locator("#reason").fill("Left the company this week");
  await page.getByRole("button", { name: "সংরক্ষণ" }).click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  const s = await stateOf();
  expect(JSON.stringify(s.audit)).not.toContain("Tmp-");
});

test("support resets a password but cannot edit or create users", async ({ page }) => {
  await loginOk(page, "support1", "support-pass-1", "123456");
  await page.goto("/admin/users");
  await expect(page.getByTestId("create-link")).toHaveCount(0);
  await expect(page.getByRole("link", { name: "সম্পাদনা" })).toHaveCount(0);
  await page.getByRole("row", { name: /sr334001/ }).getByTestId("action-reset_password").click();
  await page.locator("#reason").fill("User forgot the password");
  await page.getByRole("button", { name: "পাসওয়ার্ড রিসেট" }).click();
  await expect(page.getByTestId("once-temporary_password")).toContainText("Tmp-");
  expect((await page.goto("/admin/users/new"))?.status()).toBe(403);
  // disabled users offer unlock only
  await page.goto("/admin/users");
  await expect(page.getByRole("row", { name: /locked1/ }).getByTestId("action-reset_password")).toHaveCount(0);
  await expect(page.getByRole("row", { name: /locked1/ }).getByTestId("action-unlock")).toBeVisible();
});
