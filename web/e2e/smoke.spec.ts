import { expect, test } from "@playwright/test";
import { MOCK, login, loginOk, resetMock } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

test("login page is Bangla first and switches to English", async ({ page }) => {
  await page.goto("/login");
  await expect(page.locator("html")).toHaveAttribute("lang", "bn");
  await expect(page.getByRole("heading", { name: "সাইন ইন" })).toBeVisible();
  await page.getByRole("link", { name: "English" }).click();
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
});

test("TSO logs in and sees an empty dashboard scoped by the server, in Bangla then English", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("heading", { name: "ড্যাশবোর্ড", level: 1 })).toBeVisible();
  await expect(page.getByTestId("scope-node")).toContainText("Banani");
  await expect(page.getByTestId("dashboard-empty")).toBeVisible();
  // Bengali digits in the business date (Dhaka).
  await expect(page.getByTestId("business-date")).toHaveText(/[০-৯]/);
  // The TSO has no admin menu.
  await expect(page.getByRole("link", { name: "ক্লাস্টার" })).toHaveCount(0);
  await page.getByRole("link", { name: "English" }).click();
  await expect(page.getByRole("heading", { name: "Dashboard", level: 1 })).toBeVisible();
  await expect(page.getByTestId("business-date")).not.toHaveText(/[০-৯]/);
  await expect(page.getByTestId("who")).toContainText("Territory sales officer");
});

test("wrong password shows a localised error and no session", async ({ page }) => {
  await login(page, "tso334", "wrong");
  await expect(page.getByTestId("login-error")).toHaveText("ইউজারনেম বা পাসওয়ার্ড ভুল।");
  await page.goto("/");
  await expect(page).toHaveURL(/\/login/);
});

test("the browser never holds a token: HttpOnly cookies only, no storage, no token in any BFF body", async ({ page, context }) => {
  const bodies: string[] = [];
  page.on("response", async (r) => {
    if (r.url().includes("/api/bff/")) bodies.push(await r.text().catch(() => ""));
  });
  await loginOk(page, "tso334", "tso-pass-1");
  await expect(page.getByTestId("who")).toBeVisible();
  const cookies = await context.cookies();
  const rt = cookies.find((c) => c.name === "aron_rt");
  const sess = cookies.find((c) => c.name === "aron_sess");
  expect(rt?.httpOnly).toBe(true);
  expect(sess?.httpOnly).toBe(true);
  expect(rt?.sameSite).toBe("Strict");
  expect(await page.evaluate(() => document.cookie)).not.toMatch(/aron_(rt|sess)/);
  expect(await page.evaluate(() => JSON.stringify([localStorage, sessionStorage]))).toBe(JSON.stringify([{}, {}]));
  expect(sess?.value).not.toContain("at.");
  for (const b of bodies) expect(b).not.toMatch(/access_token|refresh_token|"at\./);
});

test("role gate: TSO gets 403 on admin pages and on the admin BFF; anonymous goes to login", async ({ page, request }) => {
  await page.goto("/admin/clusters");
  await expect(page).toHaveURL(/\/login\?next=%2Fadmin%2Fclusters/);

  await loginOk(page, "tso334", "tso-pass-1");
  await expect(page.getByTestId("who")).toBeVisible();
  const res = await page.goto("/admin/clusters");
  expect(res?.status()).toBe(403);
  await expect(page.getByTestId("forbidden")).toBeVisible();
  const audit = await page.goto("/admin/audit");
  expect(audit?.status()).toBe(403);

  const api = await page.request.post("/api/bff/admin/clusters", { data: { values: { name: "x", zone_id: 1 }, reason: "long enough reason" } });
  expect(api.status()).toBe(403);

  const anon = await request.post("/api/bff/admin/clusters", { data: {} });
  expect(anon.status()).toBe(401);
});

test("SR has no web access", async ({ page }) => {
  await login(page, "sr334001", "sr-pass-1");
  await expect(page.getByTestId("login-blocked")).toContainText("ওয়েব");
});

test("admin needs the TOTP step; a wrong code is refused", async ({ page }) => {
  await login(page, "admin1", "admin-pass-1", "000000");
  await expect(page.getByTestId("login-error")).toHaveText("কোডটি সঠিক নয়। কোড দেখে আবার চেষ্টা করুন।");
  await page.getByTestId("mfa-form").locator('input[name="code"]').fill("123456");
  await page.getByTestId("mfa-form").locator('button[type="submit"]').click();
  await expect(page.getByTestId("who")).toContainText("Salma Akter");
  await expect(page.getByRole("link", { name: "ক্লাস্টার" })).toBeVisible();
});

test("generated CRUD page: list, filter, edit with a mandatory reason, audit row with the reason", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.getByRole("link", { name: "ক্লাস্টার" }).click();
  await expect(page.getByTestId("data-table")).toContainText("Banani Market");

  // filter
  await page.locator('input[name="q"]').fill("Mirpur");
  await page.getByTestId("filter-bar").locator('button[type="submit"]').click();
  await expect(page.getByTestId("data-table")).toContainText("Mirpur-10");
  await expect(page.getByTestId("data-table")).not.toContainText("Banani Market");

  // edit: a reason is mandatory
  await page.getByRole("link", { name: "সম্পাদনা" }).first().click();
  const name = page.locator("#f-name");
  await expect(name).toHaveValue("Mirpur-10");
  await name.fill("Mirpur-10 Central");
  await page.getByRole("button", { name: "সংরক্ষণ" }).click();
  await expect(page.getByTestId("error-reason")).toHaveText("কমপক্ষে ১০ অক্ষর লিখুন।");
  expect((await (await fetch(`${MOCK}/__mock/state`)).json()).audit).toHaveLength(0);

  await page.locator("#reason").fill("Renamed after the market merged");
  await page.getByRole("button", { name: "সংরক্ষণ" }).click();
  await expect(page.getByTestId("form-ok")).toBeVisible();
  await expect(page.getByTestId("audit-reason")).toHaveText("Renamed after the market merged");

  const state = await (await fetch(`${MOCK}/__mock/state`)).json();
  expect(state.audit).toHaveLength(1);
  expect(state.audit[0]).toMatchObject({ entity: "cluster", action: "cluster.update", reason: "Renamed after the market merged", actor_username: "admin1" });

  // the audit page lists it
  await page.getByRole("link", { name: "অডিট লগ" }).click();
  await expect(page.getByTestId("data-table")).toContainText("Renamed after the market merged");
});

test("create needs a reason too; the BFF refuses a write without one", async ({ page }) => {
  await loginOk(page, "admin1", "admin-pass-1", "123456");
  await page.goto("/admin/clusters/new");
  await page.locator("#f-name").fill("New Haat");
  await page.locator("#f-zone_id").fill("2");
  await page.getByRole("button", { name: "তৈরি করুন" }).click();
  await expect(page.getByTestId("error-reason")).toBeVisible();

  const res = await page.request.post("/api/bff/admin/clusters", { data: { values: { name: "No Reason", zone_id: 2 }, reason: "short" } });
  expect(res.status()).toBe(400);
  const body = await res.json();
  expect(body.code).toBe("ERR_VALIDATION");
  expect(body.errors[0].pointer).toBe("/reason");

  await page.locator("#reason").fill("Opening a new haat in zone two");
  await page.getByRole("button", { name: "তৈরি করুন" }).click();
  await expect(page.getByTestId("data-table")).toContainText("New Haat");
});

test("support can read but not change master data", async ({ page }) => {
  await loginOk(page, "support1", "support-pass-1", "123456");
  await page.goto("/admin/clusters");
  await expect(page.getByTestId("data-table")).toBeVisible();
  await expect(page.getByTestId("create-link")).toHaveCount(0);
  await expect(page.getByText("আপনি এই তালিকা দেখতে পারবেন, বদলাতে পারবেন না।")).toBeVisible();
  const res = await page.request.patch("/api/bff/admin/clusters/1", { data: { values: { name: "Hacked" }, reason: "long enough reason", version: 1 } });
  expect(res.status()).toBe(403);
});

test("logout clears the session", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await page.getByRole("button", { name: "সাইন আউট" }).click();
  await expect(page).toHaveURL(/\/login/);
  await page.goto("/");
  await expect(page).toHaveURL(/\/login/);
});
