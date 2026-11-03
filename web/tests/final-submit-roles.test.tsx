// tso-manual W1, FS-18 (build contract s5 W1 and s8 "web ... final submit"): the web Final Submit offers its Submit button only
// to roles POST /v1/day/final-submit accepts. The API takes the TSO, and a DMO or WM only when cfg.day.final_submit_delegate_roles
// lists the role (default [], bounds "subset of DMO, WM"); an ADMIN or SUPERADMIN is always refused. The page itself stays open to
// the menu's roles (preview and Delete Section Data), and the team-op proxy no longer forwards a submit the API can never accept.
// tso-manual X3 (R-W-13 (3), R-W-06 (1)): the button now follows FinalSubmitPreview.can_submit (contract 1.10.0), so a delegated DMO
// or WM gets it; a preview without the member (an older server) keeps the TSO-only rule; a role the proxy never forwards never does.
import type { ReactElement } from "react";
import { describe, expect, it, vi } from "vitest";
import { POST as teamPost } from "@/app/api/bff/team-op/route";
import { FinalSubmitPageContent } from "@/components/admin/config/final-submit-page";
import { FINAL_SUBMIT_BUTTON_ROLES, FINAL_SUBMIT_PAGE_ROLES, finalSubmitButton, TEAM_OPS } from "@/lib/admin/ops";
import type { FinalSubmitPreview } from "@/lib/admin/types";
import { ALL_ROLES } from "@/lib/auth/roles";
import { req, setupMock, signIn, token } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }), redirect: () => { throw new Error("redirect"); }, notFound: () => { throw new Error("notFound"); } }));
const session = vi.hoisted(() => ({ current: null as null | { at: string; user: { role: string } } }));
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => session.current }));
const locale = vi.hoisted(() => ({ v: "en" as "en" | "bn" }));
vi.mock("@/lib/auth/service", async (orig) => ({ ...(await orig<Record<string, unknown>>()), getLocale: async () => locale.v }));

const h = setupMock();
const LOGINS: Record<string, [string, string, boolean]> = {
  TSO: ["tso334", "tso-pass-1", false],
  DMO: ["dmo1", "dmo-pass-1", false],
  WM: ["wm1", "wm-pass-1", false],
  ADMIN: ["admin1", "admin-pass-1", true],
  SUPERADMIN: ["super1", "super-pass-1", true],
  SUPPORT: ["support1", "support-pass-1", true],
};
const cookiesOf = (role: string) => signIn(...LOGINS[role]!);
const preview: FinalSubmitPreview = { zone_id: 3341, business_date: "2026-10-29", already_submitted: false, routes: [{ route_id: 10231, route_code: "R-10231", route_name: "RouteDaily", kind: "sr", state: "sales_submitted", had_data: true, memo_count: 3, net_mtk: 10_000, ff_user_id: 1001, ff_user_name: "Testing Banani", pending_rows_reported: 0 }], warnings: [], submitted_at: null, submitted_by: null };

async function pageAs(role: string, p: FinalSubmitPreview = preview): Promise<string> {
  session.current = { at: token(await cookiesOf(role)), user: { role } };
  h.mock.state.stubs.unshift({ method: "GET", path: "/v1/day/final-submit/preview", fn: () => ({ status: 200, body: p }) }); // tso-manual X3: the latest preview wins within a test
  return html((await FinalSubmitPageContent({ searchParams: Promise.resolve({ zone: "3341" }) })) as ReactElement, locale.v);
}
const FORM = 'data-testid="final-submit-form"';

describe("who the API accepts (cfg.day.final_submit_delegate_roles, default [])", () => {
  it("the button is the TSO's alone; no admin role is ever offered it", () => {
    expect([...FINAL_SUBMIT_BUTTON_ROLES]).toEqual(["TSO"]);
    for (const r of ["ADMIN", "SUPERADMIN", "SUPPORT", "DMO", "WM", "NATIONAL", "ANALYST"] as const) expect((FINAL_SUBMIT_BUTTON_ROLES as readonly string[]).includes(r), r).toBe(false);
  });
  it("the proxy forwards a submit only for roles the API can accept: the TSO and the delegate-able DMO and WM", () => {
    expect([...TEAM_OPS["day.final-submit"].roles].sort()).toEqual(["DMO", "TSO", "WM"]);
    for (const r of ALL_ROLES) if (!["TSO", "DMO", "WM"].includes(r)) expect((TEAM_OPS["day.final-submit"].roles as readonly string[]).includes(r), r).toBe(false);
  });
  it("every button role may open the page", () => {
    for (const r of FINAL_SUBMIT_BUTTON_ROLES) expect((FINAL_SUBMIT_PAGE_ROLES as readonly string[]).includes(r)).toBe(true);
  });
});

describe("the page per role", () => {
  it("TSO: the preview and the Submit form", async () => {
    const m = await pageAs("TSO");
    expect(m).toContain('data-testid="final-submit-form"');
    expect(text(m)).toContain("RouteDaily");
  });
  for (const role of ["ADMIN", "SUPERADMIN", "DMO", "WM"]) {
    it(`${role}: the preview without the Submit form (the API would answer 403)`, async () => {
      const m = await pageAs(role);
      expect(m).not.toContain('data-testid="forbidden"');
      expect(text(m)).toContain("RouteDaily");
      expect(m).not.toContain('data-testid="final-submit-form"');
    });
  }
  it("ADMIN keeps Delete Section Data on the preview", async () => {
    expect(await pageAs("ADMIN")).toContain('data-testid="void-10231"');
  });
  it("SUPPORT: the page is not theirs (the menu's roles)", async () => {
    expect(await pageAs("SUPPORT")).toContain('data-testid="forbidden"');
  });
  it("the menu lists Final Submit for every page role, WM included (fix round)", async () => {
    const { menuFor } = await import("@/lib/menu/menu");
    for (const r of FINAL_SUBMIT_PAGE_ROLES) expect(menuFor(r).some((sec) => sec.items.some((i) => i.href === "/final-submit")), r).toBe(true);
  });
});

describe("the Submit button follows FinalSubmitPreview.can_submit (R-W-13 (3))", () => {
  it("the rule: can_submit when sent; the TSO-only rule without it; never a role the proxy does not forward", () => {
    for (const r of ["TSO", "DMO", "WM"] as const) {
      expect(finalSubmitButton(r, { can_submit: true }), r).toBe(true);
      expect(finalSubmitButton(r, { can_submit: false }), r).toBe(false);
      expect(finalSubmitButton(r, {}), r).toBe(r === "TSO"); // an older server: the fallback
      expect(finalSubmitButton(r, null), r).toBe(r === "TSO"); // no zone picked yet: the view shows no preview anyway
    }
    for (const r of ["ADMIN", "SUPERADMIN", "SUPPORT", "NATIONAL"] as const) expect(finalSubmitButton(r, { can_submit: true }), r).toBe(false);
    expect(finalSubmitButton(undefined, { can_submit: true })).toBe(false);
  });
  it("a TSO whose preview says can_submit false gets no button (the server decides, not the role list)", async () => {
    expect(await pageAs("TSO", { ...preview, can_submit: false })).not.toContain(FORM);
    expect(await pageAs("TSO", { ...preview, can_submit: true })).toContain(FORM);
  });
  it("an older server (no can_submit): the TSO keeps the button, a DMO or WM does not", async () => {
    expect("can_submit" in preview).toBe(false);
    expect(await pageAs("TSO")).toContain(FORM);
    for (const role of ["DMO", "WM"]) expect(await pageAs(role), role).not.toContain(FORM);
  });
  it("ADMIN and SUPERADMIN get no button even if a server said yes (the proxy refuses them)", async () => {
    for (const role of ["ADMIN", "SUPERADMIN"]) expect(await pageAs(role, { ...preview, can_submit: true }), role).not.toContain(FORM);
  });
  it("an already submitted zone-day shows no button even when can_submit is true", async () => {
    const m = await pageAs("WM", { ...preview, can_submit: true, already_submitted: true, submitted_at: "2026-10-29T12:00:00Z", submitted_by: "tso334" });
    expect(m).not.toContain(FORM);
    expect(m).toContain('data-testid="already-submitted"');
  });
  it("bn: the delegate's button and its title in Bangla", async () => {
    locale.v = "bn";
    try {
      const m = await pageAs("DMO", { ...preview, can_submit: true });
      expect(m).toContain(FORM);
      expect(text(m)).toContain("ফাইনাল সাবমিট");
      expect(await pageAs("DMO", { ...preview, can_submit: false })).not.toContain(FORM);
    } finally {
      locale.v = "en";
    }
  });
});

describe("the team-op proxy", () => {
  const body = { zone_id: 3341, business_date: "2026-10-29", client_uuid: "44444444-4444-4444-8444-444444444444" };
  const post = async (role: string) => teamPost(req("/api/bff/team-op", "POST", { op: "day.final-submit", body }, await cookiesOf(role)));
  it("ADMIN and SUPERADMIN are refused before the API (nothing is forwarded)", async () => {
    h.stub({ method: "POST", path: "/v1/day/final-submit", fn: () => ({ status: 200, body: { zone_id: 3341 } }) });
    expect((await post("ADMIN")).status).toBe(403);
    expect((await post("SUPERADMIN")).status).toBe(403);
    expect(h.calls()).toHaveLength(0);
  });
  it("the TSO's submit reaches the API with its client_uuid", async () => {
    h.stub({ method: "POST", path: "/v1/day/final-submit", fn: () => ({ status: 200, body: { zone_id: 3341 } }) });
    expect((await post("TSO")).status).toBe(200);
    expect(h.calls()[0]?.body).toEqual(body);
  });
  it("a delegated DMO or WM (cfg.day.final_submit_delegate_roles lists them): the preview says can_submit, the page offers the button, the proxy forwards and the API's 200 passes through (R-W-13 (3))", async () => {
    // the API with a delegate config: the TSO, DMO and WM are accepted, and the preview says so
    h.stub({ method: "POST", path: "/v1/day/final-submit", fn: () => ({ status: 200, body: { zone_id: 3341, business_date: "2026-10-29" } }) });
    for (const role of ["WM", "DMO"]) expect(await pageAs(role, { ...preview, can_submit: true }), role).toContain(FORM);
    expect((await post("WM")).status).toBe(200);
    expect((await post("DMO")).status).toBe(200);
    expect(h.calls().filter((c) => c.method === "POST").map((c) => c.role)).toEqual(["WM", "DMO"]);
  });
  it("without the delegate config the preview says can_submit false: a DMO or WM sees the preview and no button", async () => {
    for (const role of ["WM", "DMO"]) {
      const m = await pageAs(role, { ...preview, can_submit: false });
      expect(m, role).not.toContain(FORM);
      expect(text(m), role).toContain("RouteDaily");
    }
  });
  it("a DMO's submit is forwarded and the API decides (403 with the default delegate list)", async () => {
    h.stub({ method: "POST", path: "/v1/day/final-submit", fn: (c) => (c.role === "TSO" ? { status: 200, body: {} } : { status: 403, body: { type: "x", title: "x", status: 403, code: "ERR_FORBIDDEN" } }) });
    expect((await post("DMO")).status).toBe(403);
    expect(h.calls()).toHaveLength(1);
  });
});
