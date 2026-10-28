// tso-manual W1, FB-03 and FB-12 (build contract s5 W1; contract 1.10.0 Feedback members; D-TSO-MAN-36): the feedback inbox shows
// the category by its label (suggestion, feedback), the author's username and zone, the photo as a thumbnail read through
// GET /v1/media/{uuid}/read-url, the business date with a from/to filter, and lets every role the API lets triage (SUPPORT,
// ADMIN, SUPERADMIN) set the status. The list runs against the contract mock (mock/master-seed.ts feedback rows).
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { feedback } from "@/app/admin/_entities/feedback";
import { EntityActionPage, EntityListPage } from "@/components/admin/crud/pages";
import { signIn, setupMock, token } from "./helpers/harness";
import { text } from "./helpers/render";

const session = vi.hoisted(() => ({ current: null as null | { at: string; user: { role: string } } }));
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => session.current, currentPath: async () => "/admin/feedback" }));
const locale = vi.hoisted(() => ({ v: "en" as "en" | "bn" }));
vi.mock("@/lib/auth/service", async (orig) => ({ ...(await orig<Record<string, unknown>>()), getLocale: async () => locale.v }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }), notFound: () => { throw new Error("notFound"); }, redirect: () => { throw new Error("redirect"); } }));
vi.mock("@/components/admin/crud/entity-form", async () => {
  const { createElement } = await import("react");
  return { EntityForm: () => createElement("div", { "data-testid": "form" }) };
});

const h = setupMock();
const A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"; // tso334, feedback, photo, zone 14, 2026-10-05
const PHOTO = "fb000000-0000-4000-8000-0000000000a1";
async function as(username: string, password: string, role: string) {
  session.current = { at: token(await signIn(username, password, true)), user: { role } };
}
const list = async (sp: Record<string, string> = {}) => renderToStaticMarkup(await EntityListPage({ meta: feedback, searchParams: sp }));
const rowsOf = (m: string) => (m.match(/<tr/g) ?? []).length - 1;

describe("FB-03: the mock seed (D-TSO-MAN-36 categories)", () => {
  it("the categories are suggestion and feedback, Bangla pending (English shown)", async () => {
    expect(h.mock.state.codeLists.feedback_category).toMatchObject([{ code: "suggestion", label_en: "Suggestion", label_bn: null }, { code: "feedback", label_en: "Feedback", label_bn: null }]);
  });
});

describe("FB-12: the inbox", () => {
  it("category label, username, zone, business date and the photo thumbnail from its read URL", async () => {
    await as("msupport1", "support-pass-1", "SUPPORT");
    const m = await list();
    const t = text(m);
    for (const s of ["Feedback", "Suggestion", "tso334", "tso335", "Z-334-1 · Banani Zone 1", "Photo", "Zone", "5 October 2026"]) expect(t).toContain(s);
    expect(t).not.toContain("NaN");
    expect((m.match(/data-testid="media-thumb"/g) ?? []).length).toBe(1); // only one row has a photo
    expect(m).toMatch(/<img[^>]+src="data:image\/svg\+xml/);
  });
  it("a photo whose read URL fails shows a dash; the rest of the page stays", async () => {
    h.stub({ method: "GET", path: `/v1/media/${PHOTO}/read-url`, fn: () => ({ status: 404, body: { type: "x", title: "x", status: 404, code: "ERR_NOT_FOUND" } }) });
    await as("msupport1", "support-pass-1", "SUPPORT");
    const m = await list();
    expect(m).not.toContain("media-thumb");
    expect(text(m)).toContain("Memo print is slow");
  });
  it("the from/to filter narrows by business date and is passed to the API as from and to", async () => {
    await as("madmin1", "admin-pass-1", "ADMIN");
    expect(rowsOf(await list())).toBe(3);
    const m = await list({ from: "2026-10-06", to: "2026-10-19" });
    expect(rowsOf(m)).toBe(1);
    expect(text(m)).toContain("Route list missing an outlet");
    expect(m).toContain('name="from"');
    expect(m).toContain('name="to"');
    expect(rowsOf(await list({ from: "not-a-date" }))).toBe(3); // a bad date is dropped, not sent
  });
  it("SUPPORT, ADMIN and SUPERADMIN get the status action (the API's triage roles); the action page shows the photo", async () => {
    for (const [u, p, r] of [["msupport1", "support-pass-1", "SUPPORT"], ["madmin1", "admin-pass-1", "ADMIN"], ["msuper1", "super-pass-1", "SUPERADMIN"]] as const) {
      await as(u, p, r);
      expect(await list(), r).toContain('data-testid="action-status"');
    }
    await as("msupport1", "support-pass-1", "SUPPORT");
    const page = renderToStaticMarkup(await EntityActionPage({ meta: feedback, id: A, actionKey: "status", searchParams: {} }));
    expect(page).toContain("media-thumb");
    expect(text(page)).toContain("tso334");
  });
  it("a category the list does not know shows its code, never NaN", async () => {
    (h.mock.state.tables.feedback![0] as Record<string, unknown>).category_code = "retired_kind";
    await as("madmin1", "admin-pass-1", "ADMIN");
    const t = text(await list());
    expect(t).toContain("retired_kind");
    expect(t).not.toContain("NaN");
  });
  it("bn: the column labels in Bangla, English category labels until AKTCL supplies Bangla", async () => {
    locale.v = "bn";
    await as("madmin1", "admin-pass-1", "ADMIN");
    const t = text(await list());
    expect(t).toContain("ছবি");
    expect(t).toContain("জোন");
    expect(t).toContain("Feedback");
    locale.v = "en";
  });
});
