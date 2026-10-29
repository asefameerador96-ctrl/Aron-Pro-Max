// tso-manual X3, lead ruling R-W-13 (build contract docs/requests/tso-manual-build-2026-10.md s10), items 1, 2 and 7 (items 3 to 6
// and 8 are pinned next to the W1 tests they extend: final-submit-roles, tso-daily-tracking, national-exact-pct, national-server,
// drill-columns). Pages and BFF handlers run against the contract mock; en and bn.
//   (1) the support desk's Tickets entry draws the inbox icon (kit MENU_ICON, and the sidebar's own map, which is what it draws);
//   (2) the leave-decision BFF and the page allow the 500 characters of DecisionRequest.note;
//   (7) `support` is a grant module of the access editor, with its bn and en label, accepted end to end.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it, vi } from "vitest";
import { POST as leavePost } from "@/app/api/bff/leave-decision/route";
import { decisionBody, NOTE_MAX, noteTooLong } from "@/app/(dashboards)/leave/leave-rows";
import { ADMIN_MENU } from "@/app/admin/menu";
import { UserAccessEditor } from "@/components/admin/user-access-editor";
import { ICON_PATHS, menuIcon } from "@/components/kit/icons";
import { NavLink } from "@/components/nav-link";
import { navIconFor } from "@/components/nav-icons";
import type { Schemas } from "@/contract/types";
import { GRANT_MODULES, MODULE_KEYS, type UserAccess } from "@/lib/admin/access-grants";
import { formatNumber, t } from "@/lib/i18n";
import { op, req, setupMock, signIn, superadmin } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }), usePathname: () => "/admin/tickets" }));

const h = setupMock();
const CONTRACT = readFileSync(resolve(__dirname, "../../contract/openapi.yaml"), "utf8");

describe("(1) the Tickets entry's icon", () => {
  it("the admin menu's Tickets entry is admin-tickets, and both icon maps give it the inbox (no dot)", () => {
    expect(ADMIN_MENU.find((i) => i.href === "/admin/tickets")?.id).toBe("admin-tickets");
    expect(menuIcon("admin-tickets")).toBe("inbox");
    expect(ICON_PATHS.inbox).toBeTruthy();
    expect(navIconFor("admin-tickets")).toBe("inbox");
    expect(navIconFor("admin-tickets")).not.toBe(navIconFor("no-such-entry")); // the fallback dot
  });
  it("the sidebar link draws the inbox glyph beside the label, in en and bn", () => {
    for (const locale of ["en", "bn"] as const) {
      const m = html(<NavLink href="/admin/tickets" icon={navIconFor("admin-tickets")} label={t(locale, "tickets.menu")} />, locale);
      expect(m).toContain('d="M4 13l2.5-8h11l2.5 8 M4 13v6h16v-6 M4 13h4.5a3.5 3.5 0 0 0 7 0H20"');
      expect(text(m)).toBe(locale === "en" ? "Tickets" : "টিকিট");
      expect(m).toContain('aria-current="page"');
    }
  });
});

describe("(2) the leave decision note allows 500 characters (DecisionRequest.note maxLength 500)", () => {
  const L1 = "11111111-1111-4111-8111-111111111111"; // tso334, casual, pending
  const post = async (body: unknown) => leavePost(req("/api/bff/leave-decision", "POST", body, await signIn("dmo1", "dmo-pass-1")));
  it("the page and the BFF share one limit, the contract's", () => {
    expect(NOTE_MAX).toBe(500);
    expect(/DecisionRequest:[\s\S]*?note: \{ type: \[string, 'null'\], maxLength: (\d+) \}/.exec(CONTRACT)?.[1]).toBe(String(NOTE_MAX));
    expect(noteTooLong("ক".repeat(500))).toBe(false);
    expect(noteTooLong(`  ${"ক".repeat(500)}  `)).toBe(false); // trimmed first, as the BFF does
    expect(noteTooLong("ক".repeat(501))).toBe(true);
  });
  it("a 500-character Bangla note reaches the API and is stored; 301 to 500 were refused before", async () => {
    const note = "ক".repeat(500);
    const r = await post(decisionBody(L1, "reject", note));
    expect(r.status).toBe(200);
    expect(h.mock.state.dash.leaveNotes[L1]).toBe(note);
  });
  it("a 400-character English note passes (the old proxy cap was 300)", async () => {
    expect((await post(decisionBody(L1, "reject", "x".repeat(400)))).status).toBe(200);
  });
  it("501 characters are refused at the BFF before the API; the leave stays pending", async () => {
    expect((await post({ leave_uuid: L1, decision: "reject", note: "x".repeat(501) })).status).toBe(400);
    expect(h.mock.state.dash.leave.find((l) => l.leave_uuid === L1)?.status).toBe("pending");
  });
  it("the page's hint names 500, in en and bn", () => {
    expect(t("en", "tsoman.leave.note_hint", { n: formatNumber("en", NOTE_MAX) })).toBe("Up to 500 characters, kept with the decision.");
    expect(t("bn", "tsoman.leave.note_hint", { n: formatNumber("bn", NOTE_MAX) })).toContain("সর্বোচ্চ ৫০০ অক্ষর");
  });
});

describe("(7) support is a grant module (R-F-11) and the editor offers it", () => {
  it("GRANT_MODULES is exactly the contract's AdminModule enum, support included", () => {
    const yaml = /\n {4}AdminModule:\n {6}type: string\n {6}enum: \[([^\]]+)\]/.exec(CONTRACT)?.[1];
    expect(yaml?.split(",").map((s) => s.trim())).toEqual([...GRANT_MODULES]);
    const typed: readonly Schemas["AdminModule"][] = GRANT_MODULES; // a module the contract does not know fails the typecheck
    expect(typed).toContain("support");
    expect(MODULE_KEYS.support).toBe("grant.module.support");
  });
  const access: UserAccess = { user_id: 3001, role: "ADMIN", version: 2, modules: { support: "w", outlets: "r" }, brand_ids: [] };
  it("the editor lists Support tickets with its level, in en and bn", () => {
    const en = html(<UserAccessEditor access={access} brands={[]} canWrite />, "en");
    const row = /<tr data-testid="grant-support">[\s\S]*?<\/tr>/.exec(en)?.[0] ?? "";
    expect(text(row)).toBe("Support tickets");
    expect(row).toMatch(/checked="" value="w"/);
    expect(row).toContain('aria-label="Support tickets: ');
    const bn = html(<UserAccessEditor access={access} brands={[]} canWrite />, "bn");
    const bnRow = /<tr data-testid="grant-support">[\s\S]*?<\/tr>/.exec(bn)?.[0] ?? "";
    expect(text(bnRow)).toBe("সাপোর্ট টিকিট");
    expect(bnRow).toContain('aria-label="সাপোর্ট টিকিট: ');
  });
  it("the Super Admin grants support at w through the whitelisted op; it is stored", async () => {
    const res = await op(await superadmin(), { op: "user-access.put", params: { id: 3001 }, body: { modules: { support: "w" } }, reason: "Support desk for the pilot admin", version: 2 });
    expect(res.status).toBe(200);
    expect(h.mock.state.custom.access[3001]).toMatchObject({ version: 3, modules: { support: "w" } });
  });
});
