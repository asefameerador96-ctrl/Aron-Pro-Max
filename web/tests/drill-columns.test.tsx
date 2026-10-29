// tso-manual W1, DASH-13 (build contract s5 W1, s8 "web reopen, final submit, drill, leave, feedback"; D-TSO-MAN-12; F1 ask 11):
// the Login / Bikroy Joma drill-down lists (route, user) rows from POST /v1/dashboards/national/login-submit/users with the app's
// columns (name or "SR Not Set", User Name, Route, Dep Name = the zone, role), one table per tab, the route count of each tab,
// and a "list cut, narrow the filter" note when the server says `truncated`. The page runs against the contract mock.
import type { ReactElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Schemas, ScopeSummary } from "@/contract/types";
import Drill from "@/app/(dashboards)/drill/[kind]/page";
import { LOGIN_TABS, loginUserColumns, loginUserRows, loginUsersFilter, routeCountText, truncatedText, type LoginUsers } from "@/app/(dashboards)/drill/[kind]/login-users";
import { setupMock, signIn, token } from "./helpers/harness";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {} }), notFound() { throw new Error("notFound"); } }));
const session = vi.hoisted(() => ({ current: null as null | { at: string; scope: ScopeSummary; user: { role: string } } }));
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => session.current }));
const locale = vi.hoisted(() => ({ v: "en" as "en" | "bn" }));
vi.mock("@/lib/auth/service", async (orig) => ({ ...(await orig<Record<string, unknown>>()), getLocale: async () => locale.v }));

const h = setupMock();
const PATH = "/v1/dashboards/national/login-submit/users";
type Row = Schemas["LoginSubmitUserRow"];
const row = (o: Partial<Row> = {}): Row => ({ route_id: 10, route_code: "R-10", route_name: "Apsis Route", zone_id: 7, dep_name: "Banani - Test", user_id: 1001, username: "sr334001", display_name: "SR - Testing Banani", role: "SR", ...o });
const page = (o: Partial<LoginUsers> = {}): LoginUsers => ({ date: "2026-10-29", till_time: true, as_of: "2026-10-29T06:00:00.000Z", last_updated_at: null, tab: "not_logged_in", route_count: 1, truncated: false, items: [row()], ...o });
const plain = (m: string) => m.replace(/<[^>]+>/g, " ").replace(/&amp;/g, "&").replace(/\s+/g, " ");

async function as(username: string, password: string, role: string, scope: ScopeSummary) {
  session.current = { at: token(await signIn(username, password)), scope, user: { role } };
}
const tsoScope: ScopeSummary = { scope_version: 7, nodes: [{ type: "territory", id: 334, code: "T-334", name: "Banani" }] };
const dmoScope: ScopeSummary = { scope_version: 4, nodes: [{ type: "division", id: 10, code: "D-10", name: "Dhaka North" }] };
const render = async (q: Record<string, string> = {}) => renderToStaticMarkup((await Drill({ params: Promise.resolve({ kind: "login" }), searchParams: Promise.resolve(q) })) as ReactElement);

beforeEach(() => {
  locale.v = "en";
});

describe("rows and columns (D-TSO-MAN-12, screenshot 8)", () => {
  it("columns are the app card's lines plus the role, in en and bn", () => {
    expect(loginUserColumns("en").map((c) => c.header)).toEqual(["Name", "User Name", "Route", "Dep Name", "Role"]);
    expect(loginUserColumns("bn").map((c) => c.header)).toEqual(["নাম", "ইউজার নেম", "রুট", "ডেপ নেম", "ভূমিকা"]);
  });
  it("an SR row shows the full name, the username, the route name and the zone as Dep Name", () => {
    expect(loginUserRows("en", [row()])[0]!.cells).toEqual(["SR - Testing Banani", "sr334001", "Apsis Route", "Banani - Test", "SR"]);
  });
  it("a vacant route's SR row is 'SR Not Set' with no User Name; AMO rows carry the AMO role", () => {
    const rows = loginUserRows("en", [row({ user_id: null, username: null, display_name: null }), row({ user_id: 6334, username: "amo6334", display_name: "AMO-6334", role: "AMO" })]);
    expect(rows.map((r) => r.cells)).toEqual([
      ["SR Not Set", "—", "Apsis Route", "Banani - Test", "SR"],
      ["AMO-6334", "amo6334", "Apsis Route", "Banani - Test", "AMO"],
    ]);
    expect(new Set(rows.map((r) => r.key)).size).toBe(2);
    expect(loginUserRows("bn", [row({ user_id: null, username: null, display_name: null })])[0]!.cells[0]).toBe("এসআর নির্ধারিত নয়");
  });
  it("the same AMO under two routes is two rows (AMO rows follow the route, screenshot 8)", () => {
    const amo = { user_id: 6334, username: "amo6334", display_name: "AMO-6334", role: "AMO" as const };
    const rows = loginUserRows("en", [row({ route_id: 1, route_name: "Apsis Route", ...amo }), row({ route_id: 2, route_name: "Apsis Route 2", ...amo })]);
    expect(rows.map((r) => r.cells[2])).toEqual(["Apsis Route", "Apsis Route 2"]);
    expect(rows[0]!.key).not.toBe(rows[1]!.key);
  });
  it("a drill opened for one route keeps that route's rows only", () => {
    expect(loginUserRows("en", [row({ route_id: 1 }), row({ route_id: 2 })], 2).map((r) => r.key)).toEqual(["2:SR:1001"]);
  });
  it("the filter carries the picked geo ids and the date, never the route (the national filter has no route ids)", () => {
    expect(loginUsersFilter({ territory: [334], zone: [3342], route: [10233] }, "2026-10-29")).toEqual({ date: "2026-10-29", territory_ids: [334], zone_ids: [3342] });
    expect(loginUsersFilter({}, "2026-10-29")).toEqual({ date: "2026-10-29" });
  });
  it("a picked route without a picked zone sends the route's own zone, so a cut list cannot leave the route out", () => {
    expect(loginUsersFilter({ territory: [334], route: [10233] }, "2026-10-29", 3342)).toEqual({ date: "2026-10-29", territory_ids: [334], zone_ids: [3342] });
    expect(loginUsersFilter({ zone: [3341], route: [10233] }, "2026-10-29", 3342).zone_ids).toEqual([3341]); // the picked zone wins
    expect(loginUsersFilter({ territory: [334] }, "2026-10-29", 3342)).toEqual({ date: "2026-10-29", territory_ids: [334] }); // no route, no zone
    expect(loginUsersFilter({ route: [10233] }, "2026-10-29", null)).toEqual({ date: "2026-10-29" }); // zone unreadable: as before
  });
});

describe("route count and the 'list cut' note (F1 ask 11)", () => {
  it("each tab says how many routes it holds on the drill's date (never 'today'), Bengali digits in bn", () => {
    expect(routeCountText("en", page({ route_count: 1186 }))).toBe("1,186 routes without a login on 29 October 2026.");
    expect(routeCountText("en", page({ tab: "not_uploaded", route_count: 3, date: "2026-10-20" }))).toBe("3 routes logged in without Bikroy Joma on 20 October 2026.");
    const bnText = routeCountText("bn", page({ route_count: 12 }));
    expect(bnText).toContain("১২");
    expect(bnText).toContain("তারিখে");
    expect(bnText).not.toContain("আজ");
  });
  it("the note shows only when the server cut the list, with the number of rows shown", () => {
    expect(truncatedText("en", page())).toBeNull();
    const cut = page({ truncated: true, route_count: 2400, items: Array.from({ length: 2000 }, (_, i) => row({ route_id: i + 1 })) });
    expect(truncatedText("en", cut)).toBe("The list is cut at 2,000 rows. Narrow the filter (a territory or a zone) to see every row.");
    expect(truncatedText("bn", cut)).toContain("২,০০০");
  });
});

describe("the drill page against the mock", () => {
  it("a TSO sees both tabs from login-submit/users: the seeded Not Uploaded route with its SR and the AMO of the zone", async () => {
    await as("tso334", "tso-pass-1", "TSO", tsoScope);
    const m = await render();
    expect(m).toContain('data-testid="table-not-logged-in"');
    expect(m).toContain('data-testid="table-not-uploaded"');
    expect(m).toContain('data-testid="table-empty-not-logged-in"'); // every SR route of territory 334 logged in on the seeded day
    const t = plain(m);
    expect(t).toContain("Not Logged In");
    expect(t).toContain("Not Uploaded");
    expect(t).toContain("1 routes logged in without Bikroy Joma on ");
    for (const cell of ["Jamal Uddin", "sr1003", "Banani South", "Banani Zone 2", "Rafiq Amin", "amo1004"]) expect(t).toContain(cell);
    expect(t).not.toContain("Gulshan"); // territory 335 is outside the token's reach
    expect(m).not.toContain("truncated-");
  });
  it("each tab is one POST with ?tab=; a TSO's own territory level is locked, so a foreign territory id is never sent", async () => {
    const seen: { tab: string; body: unknown }[] = [];
    h.stub({ method: "POST", path: PATH, fn: (c) => { seen.push({ tab: c.query.tab!, body: c.body }); return { status: 200, body: page({ tab: c.query.tab as LoginUsers["tab"] }) }; } });
    await as("tso334", "tso-pass-1", "TSO", tsoScope);
    await render({ territory: "335", zone: "3351" });
    expect(seen.map((s) => s.tab).sort()).toEqual([...LOGIN_TABS].sort());
    for (const s of seen) expect(s.body).toEqual({ date: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/), zone_ids: [3351] }); // 335 is not the TSO's territory: dropped
  });
  it("a zone outside the token's reach only narrows: the server's reach still decides, so no row of it comes back", async () => {
    await as("tso334", "tso-pass-1", "TSO", tsoScope);
    const m = await render({ zone: "3351" });
    expect(plain(m)).not.toContain("Gulshan");
    expect(m).toContain('data-testid="table-empty-not-uploaded"');
  });
  it("a drill for one route: its zone is looked up and sent, and the scope-wide route count is not shown above its rows", async () => {
    const seen: unknown[] = [];
    h.stub({ method: "POST", path: PATH, fn: (c) => { seen.push(c.body); return { status: 200, body: page({ tab: c.query.tab as LoginUsers["tab"], route_count: 1186, items: [row({ route_id: 10233, route_name: "Banani South", zone_id: 3342 }), row({ route_id: 99 })] }) }; } });
    await as("dmo1", "dmo-pass-1", "DMO", dmoScope);
    const m = await render({ territory: "334", route: "10233", date: "2026-10-27" });
    expect(seen).toHaveLength(2);
    for (const b of seen) expect(b).toEqual({ date: "2026-10-27", territory_ids: [334], zone_ids: [3342] }); // Banani South is in zone 3342
    const t = plain(m);
    expect(t).toContain("Banani South");
    expect(t).not.toContain("1,186 routes");
    expect(t).not.toContain("without a login today");
  });
  it("a DMO's zone pick narrows the request to that zone", async () => {
    const seen: unknown[] = [];
    h.stub({ method: "POST", path: PATH, fn: (c) => { seen.push(c.body); return { status: 200, body: page() }; } });
    await as("dmo1", "dmo-pass-1", "DMO", dmoScope);
    await render({ territory: "334", zone: "3342", date: "2026-10-28" });
    expect(seen[0]).toEqual({ date: "2026-10-28", territory_ids: [334], zone_ids: [3342] });
  });
  it("a cut list shows the 'narrow the filter' note on its tab; the route count still counts every route", async () => {
    h.stub({ method: "POST", path: PATH, fn: (c) => ({ status: 200, body: c.query.tab === "not_logged_in" ? page({ truncated: true, route_count: 2412, items: Array.from({ length: 2000 }, (_, i) => row({ route_id: i + 1, route_name: `Route ${i + 1}` })) }) : page({ tab: "not_uploaded" }) }) });
    await as("dmo1", "dmo-pass-1", "DMO", dmoScope);
    const m = await render();
    expect(m).toContain('data-testid="truncated-not-logged-in"');
    expect(m).not.toContain('data-testid="truncated-not-uploaded"');
    expect(plain(m)).toContain("2,412 routes without a login on 29 October 2026.");
    expect(plain(m)).toContain("The list is cut at 2,000 rows.");
  });
  it("a failed tab shows its error with Retry, the other tab still shows its rows", async () => {
    h.stub({ method: "POST", path: PATH, fn: (c) => (c.query.tab === "not_uploaded" ? { status: 500, body: { type: "x", title: "x", status: 500, code: "ERR_INTERNAL" } } : { status: 200, body: page() }) });
    await as("dmo1", "dmo-pass-1", "DMO", dmoScope);
    const m = await render();
    expect(m).toContain('data-testid="table-error-not-uploaded"');
    expect(plain(m)).toContain("SR - Testing Banani");
  });
  it("bn: tab names and the vacant route in Bangla, digits in Bengali", async () => {
    h.stub({ method: "POST", path: PATH, fn: (c) => ({ status: 200, body: page({ tab: c.query.tab as LoginUsers["tab"], route_count: 2, items: [row({ user_id: null, username: null, display_name: null })] }) }) });
    await as("dmo1", "dmo-pass-1", "DMO", dmoScope);
    locale.v = "bn";
    const t = plain(await render());
    expect(t).toContain("লগইন করেননি");
    expect(t).toContain("এসআর নির্ধারিত নয়");
    expect(t).toContain("তারিখে ২টি রুটে লগইন হয়নি।");
  });
});

describe("the mock operation (web/mock fixture of queryNationalLoginSubmitUsers)", () => {
  const call = async (username: string, password: string, tab: string | null, body: unknown = {}) => {
    const at = token(await signIn(username, password));
    const res = await fetch(`${process.env.ARON_API_BASE_URL}${PATH}${tab ? `?tab=${tab}` : ""}`, { method: "POST", headers: { Authorization: `Bearer ${at}`, "Content-Type": "application/json" }, body: JSON.stringify(body) });
    return { status: res.status, body: (await res.json()) as LoginUsers };
  };
  it("the tab is required; a BRAND user gets 403 (as the login-submit card)", async () => {
    expect((await call("dmo1", "dmo-pass-1", null)).status).toBe(400);
    expect((await call("dmo1", "dmo-pass-1", "everyone")).status).toBe(400);
    expect((await call("brand1", "brand-pass-1", "not_logged_in")).status).toBe(403);
  });
  it("a DMO sees both territories; a zone filter narrows; the SR row comes before the AMO rows of its route", async () => {
    const all = await call("dmo1", "dmo-pass-1", "not_uploaded");
    expect(all.status).toBe(200);
    expect(all.body.items.map((r) => `${r.route_name}/${r.role}/${r.username}`)).toEqual(["Banani South/SR/sr1003", "Banani South/AMO/amo1004", "Gulshan West/SR/sr1006"]);
    expect(all.body).toMatchObject({ tab: "not_uploaded", route_count: 2, truncated: false });
    const zone = await call("dmo1", "dmo-pass-1", "not_uploaded", { zone_ids: [3351] });
    expect(zone.body.items.map((r) => r.route_name)).toEqual(["Gulshan West"]);
    expect(zone.body.items[0]!.dep_name).toBe("Gulshan Zone 1");
  });
});
