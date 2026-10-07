// Checker ADM-4 (F-ADM-028 feedback inbox, F-ADM-076 SR lifecycle): page-level behaviour. A failing test is a confirmed defect.
import { beforeEach, describe, expect, it, vi } from "vitest";

let ROLE = "ADMIN";
let LOCALE: "en" | "bn" = "en";
let rows: Record<string, unknown>[] = [];
vi.mock("@/lib/api/raw", () => ({
  rawRequest: vi.fn(async (req: { path: string }) => ({ ok: true, status: 200, data: req.path.startsWith("/v1/feedback") ? { items: rows, next_cursor: null } : { items: [], next_cursor: null }, response: new Response(null) })),
}));
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => ({ user: { role: ROLE }, at: "tok" }), currentPath: async () => "/admin/x" }));
vi.mock("@/lib/auth/service", () => ({ getLocale: async () => LOCALE }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }), notFound: () => { throw new Error("notFound"); }, redirect: () => { throw new Error("redirect"); } }));
vi.mock("@/components/admin/crud/entity-form", async () => {
  const { createElement } = await import("react");
  return { EntityForm: (p: { fields: { name: string; label: string }[] }) => createElement("div", { "data-testid": "form" }, p.fields.map((f) => f.label).join("|")) };
});

import { renderToStaticMarkup } from "react-dom/server";
import { feedback } from "@/app/admin/_entities/feedback";
import SrLifecyclePage from "@/app/admin/sr-lifecycle/page";
import { EntityActionPage, EntityListPage } from "@/components/admin/crud/pages";
import { en } from "@/lib/i18n/messages-en";
import { bn } from "@/lib/i18n/messages-bn";
import { text } from "./helpers/render";

const F1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const row = (o: Record<string, unknown> = {}) => ({ feedback_uuid: F1, user_id: 1003, category_code: "app", title: "T", description: "D", photo_uuid: null, created_at: "2026-10-05T04:00:00.000Z", status: "new", ...o });
const list = async () => renderToStaticMarkup(await EntityListPage({ meta: feedback, searchParams: {} }));
const action = async () => renderToStaticMarkup(await EntityActionPage({ meta: feedback, id: F1, actionKey: "status", searchParams: {} }));
const FORBIDDEN = /forbidden|not allowed|permission/i;
const E = en as Record<string, string>;
const B = bn as Record<string, string>;

beforeEach(() => { ROLE = "ADMIN"; LOCALE = "en"; rows = [row()]; });

describe("F-ADM-028 feedback inbox", () => {
  it("CA4-1: a read-only SUPPORT user can read the feedback text (the description is in the list or a read view)", async () => {
    ROLE = "SUPPORT";
    rows = [row({ title: "Print is slow", description: "UNIQUE-DESCRIPTION-BODY the printer drops lines" })];
    expect(await list()).toContain("UNIQUE-DESCRIPTION-BODY");
  });
  for (const r of ["TSO", "DMO", "WM", "TOP", "ANALYST", "SR"]) {
    it(`CA4-3: ${r} sees Forbidden on the inbox list and the status action page`, async () => {
      ROLE = r;
      expect(text(await list())).toMatch(FORBIDDEN);
      expect(text(await action())).toMatch(FORBIDDEN);
    });
  }
  it("CA4-4: SUPPORT is read-only: no status action link, read-only note shown, action page Forbidden", async () => {
    ROLE = "SUPPORT";
    const m = await list();
    expect(m).not.toContain('data-testid="action-status"');
    expect(m).toContain(E["admin.read_only"]);
    expect(text(await action())).toMatch(FORBIDDEN);
  });
  it("CA4-5: ADMIN sees the set-status link; hostile title/description are escaped", async () => {
    rows = [row({ title: "<img src=x onerror=alert(1)>", description: "<script>alert(2)</script>" })];
    const m = await list();
    expect(m).toContain('data-testid="action-status"');
    expect(m).not.toContain("<img src=x");
    const a = await action();
    expect(a).not.toContain("<script>alert");
    expect(a).not.toContain("<img src=x");
  });
  it("CA4-6: empty inbox shows the empty message", async () => {
    rows = [];
    expect(await list()).toContain(E["common.empty"]);
  });
  it("CA4-7: a row without status or with an unknown server status still renders", async () => {
    rows = [row({ status: undefined }), row({ feedback_uuid: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", status: "reopened" })];
    expect(await list()).toContain("reopened");
  });
  it("CA4-8: long Bangla description (2000 chars) renders in full on the action page", async () => {
    rows = [row({ title: "অ".repeat(120), description: "ক".repeat(2000) })];
    expect(await action()).toContain("ক".repeat(2000));
  });
  it("CA4-9: every feedback label key exists in en and bn", () => {
    const a = feedback.actions?.[0];
    const keys = [feedback.labelKey, feedback.singularKey, ...feedback.fields.map((f) => f.labelKey), a?.labelKey, ...(a?.fields ?? []).flatMap((f) => [f.labelKey, ...Object.values(f.optionKeys ?? {})])];
    for (const k of keys) { expect(E[k as string], String(k)).toBeTruthy(); expect(B[k as string], String(k)).toBeTruthy(); }
  });
});

describe("F-ADM-076 SR lifecycle page", () => {
  const page = async () => renderToStaticMarkup(await SrLifecyclePage());
  for (const r of ["TSO", "DMO", "WM", "TOP", "ANALYST", "SR"]) {
    it(`CA4-10: ${r} sees Forbidden`, async () => {
      ROLE = r;
      const m = await page();
      expect(m).not.toContain("lifecycle-steps");
      expect(text(m)).toMatch(FORBIDDEN);
    });
  }
  it("CA4-11: ADMIN sees four steps with working targets", async () => {
    const m = await page();
    for (const h of ["/admin/users/new", "/admin/route-assignments/new", "/admin/sr-transfer", "/admin/users?role=SR"]) expect(m).toContain(`href="${h.replace(/&/g, "&amp;")}"`);
  });
  it("CA4-12: SUPPORT (read-only on users and assignments) is not handed links to pages that answer Forbidden", async () => {
    ROLE = "SUPPORT";
    const m = await page();
    expect(m).not.toContain('href="/admin/users/new"');
    expect(m).not.toContain('href="/admin/route-assignments/new"');
  });
  it("CA4-13: every lifecycle key exists in en and bn", () => {
    for (const s of ["create", "bind", "reassign", "disable"]) for (const p of ["title", "hint", "cta"]) { const k = `lifecycle.${s}.${p}`; expect(E[k], k).toBeTruthy(); expect(B[k], k).toBeTruthy(); }
  });
});
