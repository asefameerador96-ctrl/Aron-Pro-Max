import { expect, test, type Locator } from "@playwright/test";
import { MOCK, login, loginOk, resetMock, hydrated } from "./helpers";

test.beforeEach(async () => {
  await resetMock();
});

/** The whole-pixel size of an element (a sheet that is still sliding in reports fractions such as 36.0000019). */
const px = async (l: Locator) => {
  const b = (await l.boundingBox())!;
  return { width: Math.round(b.width), height: Math.round(b.height) };
};

test("login page is Bangla first and switches to English", async ({ page }) => {
  await page.goto("/login");
  await expect(page.locator("html")).toHaveAttribute("lang", "bn");
  await expect(page.getByRole("heading", { name: "সাইন ইন" })).toBeVisible();
  await page.getByRole("link", { name: "English" }).click();
  await page.goto("/overview");
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
});

// D-BRAND-01. The sign-in gate must not swallow the logo or the icons: a redirect to the login HTML would show a broken logo and
// hand the browser HTML as its favicon. The first half needs no cookie, so it runs against the real standalone server, proxy
// included; the second half checks the logo in the signed-in shell (sidebar rail, mobile bar, drawer) without moving the layout.
test("the Aron logo: public to a signed-out visitor, drawn on the login card, in the sidebar, the mobile bar and the drawer", async ({ page, request }) => {
  await page.goto("/login");
  const mark = page.locator('img[src*="aron-mark"]');
  await expect(mark).toHaveCount(1);
  await expect.poll(() => mark.evaluate((el: HTMLImageElement) => (el.complete ? el.naturalWidth : 0))).toBeGreaterThan(0);
  expect(await mark.getAttribute("alt")).toBe("");
  // The round plate keeps the 44 px slot of the old monogram; the logo sits inside it. The plate does not stretch the brand row:
  // the row stays as tall as the name and tagline beside it (the card does not move).
  const plate = mark.locator("xpath=..");
  expect(await px(plate)).toEqual({ width: 44, height: 44 });
  expect(await px(mark)).toEqual({ width: 39, height: 39 });
  const rowH = (await plate.locator("xpath=..").boundingBox())!.height;
  const textH = (await page.locator("#login-title").locator("xpath=..").boundingBox())!.height;
  expect(rowH).toBeCloseTo(textH, 0);
  // The login card keeps its 420 px width.
  expect((await page.getByRole("heading", { name: "সাইন ইন" }).locator("xpath=ancestor::section[1]").boundingBox())!.width).toBeLessThanOrEqual(420);

  // The icons exactly as Next linked them (the icon routes carry a hash query), plus the logo files and /favicon.ico.
  const linked = await page.evaluate(() => [...document.querySelectorAll('link[rel~="icon"], link[rel="apple-touch-icon"]')].map((l) => l.getAttribute("href") ?? ""));
  expect(linked.length).toBeGreaterThanOrEqual(2);
  const urls = [...new Set(["/brand/aron-mark-64.png", "/brand/aron-mark-128.png", "/brand/aron-mark-256.png", "/favicon.ico", "/icon.png", "/apple-icon.png", ...linked])];
  for (const u of urls) {
    const res = await request.get(u, { maxRedirects: 0 });
    expect(res.status(), u).toBe(200);
    const h = res.headers();
    expect(h["content-type"], u).toMatch(/^image\//);
    // The exact day cache of next.config.ts (PUBLIC_STATIC_CACHE). A loose "public, max-age=N" would also pass Next's own icon
    // header ("public, max-age=0, must-revalidate"), so it could not tell the config rule from the framework default.
    expect(h["cache-control"], u).toBe("public, max-age=86400, stale-while-revalidate=604800");
    // The security baseline of the catch-all rule still applies to these public images (only Cache-Control is overridden).
    expect(h["x-content-type-options"], u).toBe("nosniff");
    expect(h["x-frame-options"], u).toBe("DENY");
    expect(h["strict-transport-security"], u).toMatch(/^max-age=\d+/);
    expect((await res.body()).subarray(0, 4).toString("latin1"), u).not.toContain("<");
  }

  // Everything else is still behind the gate (and an ordinary page is still no-store).
  const admin = await request.get("/admin", { maxRedirects: 0 });
  expect(admin.status()).toBeGreaterThanOrEqual(300);
  expect(admin.status()).toBeLessThan(400);
  expect(admin.headers().location).toContain("/login");
  expect((await request.get("/brand/other.png", { maxRedirects: 0 })).headers().location ?? "").toContain("/login");
  expect((await request.get("/login", { maxRedirects: 0 })).headers()["cache-control"]).toContain("no-store");

  // Signed in: the shell draws the logo in its brand slots. Only what the brand change owns is asserted (the picture loads and
  // is visible); the shell's own geometry belongs to the web-dashboard lane and is not pinned here.
  await loginOk(page, "tso334", "tso-pass-1");
  const sidebar = page.getByTestId("sidebar");
  const rail = sidebar.locator('img[src*="aron-mark"]');
  await expect(rail).toBeVisible();
  await expect.poll(() => rail.evaluate((el: HTMLImageElement) => (el.complete ? el.naturalWidth : 0))).toBeGreaterThan(0);

  // Below md the bar shows the logo too; the drawer, a closed <dialog> inside the bar, carries its own and shows it when opened.
  await page.setViewportSize({ width: 390, height: 844 });
  const bar = page.getByTestId("top-bar").locator('img[src*="aron-mark"][width="28"]');
  await expect(bar).toBeVisible();
  await page.getByTestId("nav-open").click();
  const drawerMark = page.getByTestId("nav-drawer").locator('img[src*="aron-mark"]');
  await expect(drawerMark).toBeVisible();
});

test("TSO logs in and sees an empty dashboard scoped by the server, in Bangla then English", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("heading", { name: "ড্যাশবোর্ড", level: 1 })).toBeVisible();
  await expect(page.getByTestId("scope-node")).toContainText("Banani");
  await expect(page.getByTestId("national-dashboard")).toBeVisible();
  // Bengali digits in the business date (Dhaka).
  await expect(page.getByTestId("nat-date")).toHaveText(/[০-৯]/);
  // The TSO has no admin menu.
  await expect(page.getByRole("link", { name: "মাস্টার ডেটা" })).toHaveCount(0);
  await page.getByRole("link", { name: "English" }).click();
  await page.goto("/overview");
  await expect(page.getByRole("heading", { name: "Dashboard", level: 1 })).toBeVisible();
  await expect(page.getByTestId("business-date")).not.toHaveText(/[০-৯]/);
  await expect(page.getByTestId("who")).toContainText("Territory sales officer");
});

test("wrong password shows a localised error and no session", async ({ page }) => {
  await login(page, "tso334", "wrong");
  await expect(page.getByTestId("login-error")).toHaveText("ইউজার আইডি বা পাসওয়ার্ড ভুল।");
  await page.goto("/overview");
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
  await login(page, "madmin1", "admin-pass-1", "000000");
  await expect(page.getByTestId("login-error")).toHaveText("কোডটি সঠিক নয়। কোড দেখে আবার চেষ্টা করুন।");
  await page.getByTestId("mfa-form").locator('input[name="code"]').fill("123456");
  await page.getByTestId("mfa-form").locator('button[type="submit"]').click();
  await expect(page.getByTestId("who")).toContainText("Salma Akter");
  await expect(page.getByRole("link", { name: "মাস্টার ডেটা" })).toBeVisible();
});

test("generated CRUD page: list, filter, edit with a mandatory reason, audit row with the reason", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/clusters");
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
  expect(state.audit[0]).toMatchObject({ entity: "cluster", action: "cluster.update", reason: "Renamed after the market merged", actor_username: "madmin1" });

  // the audit page lists it
  await page.getByRole("link", { name: "অডিট লগ" }).click();
  await expect(page.getByTestId("data-table")).toContainText("Renamed after the market merged");
});

test("create needs a reason too; the BFF refuses a write without one", async ({ page }) => {
  await loginOk(page, "madmin1", "admin-pass-1", "123456");
  await page.goto("/admin/clusters/new");
  await page.locator("#f-name").fill("New Haat");
  await page.locator("#f-zone_id").selectOption({ label: "Z-335-1 · Mirpur Zone 1" });
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
  await loginOk(page, "msupport1", "support-pass-1", "123456");
  await page.goto("/admin/clusters");
  await expect(page.getByTestId("data-table")).toBeVisible();
  await expect(page.getByTestId("create-link")).toHaveCount(0);
  await expect(page.getByText("আপনি এই তালিকা দেখতে পারবেন, বদলাতে পারবেন না।")).toBeVisible();
  const res = await page.request.patch("/api/bff/admin/clusters/1", { data: { values: { name: "Hacked" }, reason: "long enough reason", version: 1 } });
  expect(res.status()).toBe(403);
});

test("logout clears the session", async ({ page }) => {
  await loginOk(page, "tso334", "tso-pass-1");
  // The sign-out button is a client component: a click before hydration is a native no-op (seen once in a long run).
  const signOut = page.getByRole("button", { name: "সাইন আউট" });
  await hydrated(signOut);
  await signOut.click();
  await expect(page).toHaveURL(/\/login/);
  await page.goto("/overview");
  await expect(page).toHaveURL(/\/login/);
});
