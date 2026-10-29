// tso-manual W1, LV-19 (build contract s5 W1 and s8 "web ... leave"; contract 1.10.0 LeaveApplication applicant members): the
// deciding page names the applicant (full name and username), the territory and the leave type's label instead of a user id and
// a code, and a rejection may carry an optional note. The page and the BFF run against the contract mock.
import type { ReactElement } from "react";
import { describe, expect, it, vi } from "vitest";
import { POST as leavePost } from "@/app/api/bff/leave-decision/route";
import LeavePage from "@/app/(dashboards)/leave/page";
import { LeaveDecision } from "@/app/(dashboards)/leave/leave-decision";
import { applicantLines, decisionBody, NOTE_MAX, noteTooLong, typeLabel, type Leave, type TypeLabels } from "@/app/(dashboards)/leave/leave-rows";
import { req, setupMock, signIn, token } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
const session = vi.hoisted(() => ({ current: null as null | { at: string; user: { role: string } } }));
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => session.current }));
const locale = vi.hoisted(() => ({ v: "en" as "en" | "bn" }));
vi.mock("@/lib/auth/service", async (orig) => ({ ...(await orig<Record<string, unknown>>()), getLocale: async () => locale.v }));

const h = setupMock();
const L1 = "11111111-1111-4111-8111-111111111111"; // tso334, casual, pending
const L2 = "22222222-2222-4222-8222-222222222222"; // tso335, sick, pending
const leave = (o: Partial<Leave> = {}): Leave => ({ leave_uuid: L1, user_id: 2001, leave_type_code: "casual", from_date: "2026-10-12", to_date: "2026-10-13", days: 2, reason: "Family event", status: "pending", ...o });
const labels: TypeLabels = new Map([["casual", { en: "Casual", bn: "নৈমিত্তিক" }], ["sick", { en: "Sick", bn: null }]]);

async function pageAs(username: string, password: string, role: string, lang: "en" | "bn" = "en") {
  locale.v = lang;
  session.current = { at: token(await signIn(username, password)), user: { role } };
  return html((await LeavePage({ searchParams: Promise.resolve({}) })) as ReactElement, lang);
}

describe("what a row shows", () => {
  it("the applicant by full name with the username under it", () => {
    expect(applicantLines(leave({ applicant_name: "Rahim Uddin", applicant_username: "tso334" }))).toEqual({ name: "Rahim Uddin", username: "tso334" });
  });
  it("falls back to the username, then to the user id, when a server sends no name", () => {
    expect(applicantLines(leave({ applicant_name: null, applicant_username: "tso334" }))).toEqual({ name: "tso334", username: null });
    expect(applicantLines(leave())).toEqual({ name: "#2001", username: null });
  });
  it("the type's label from the code list; Bangla when supplied, else English; an unknown code as it is", () => {
    expect(typeLabel("en", labels, "casual")).toBe("Casual");
    expect(typeLabel("bn", labels, "casual")).toBe("নৈমিত্তিক");
    expect(typeLabel("bn", labels, "sick")).toBe("Sick");
    expect(typeLabel("en", labels, "maternity")).toBe("maternity");
  });
});

describe("the decision body", () => {
  it("approve carries no note; reject carries the trimmed note, or none when it is empty", () => {
    expect(decisionBody(L1, "approve", "ignored")).toEqual({ leave_uuid: L1, decision: "approve" });
    expect(decisionBody(L1, "reject", "  Field audit that week  ")).toEqual({ leave_uuid: L1, decision: "reject", note: "Field audit that week" });
    expect(decisionBody(L1, "reject", "   ")).toEqual({ leave_uuid: L1, decision: "reject" });
  });
  it("a note longer than the proxy's limit is caught before sending", () => {
    expect(noteTooLong("ক".repeat(NOTE_MAX))).toBe(false);
    expect(noteTooLong("ক".repeat(NOTE_MAX + 1))).toBe(true);
  });
  it("the buttons keep their test ids; Reject opens the note first (no request on the first tap)", () => {
    const m = html(<LeaveDecision leaveUuid={L1} />);
    expect(m).toContain(`data-testid="approve-${L1}"`);
    expect(m).toContain(`data-testid="reject-${L1}"`);
    expect(m).not.toContain(`note-${L1}`);
  });
});

describe("the page against the mock", () => {
  it("a DMO sees name, username, territory and type label for both TSOs, with the decision buttons", async () => {
    const m = await pageAs("dmo1", "dmo-pass-1", "DMO");
    const t = text(m);
    for (const s of ["Applicant", "Territory", "Rahim Uddin", "tso334", "Banani", "Salma Begum", "tso335", "Gulshan", "Casual", "Sick"]) expect(t).toContain(s);
    expect(t).not.toContain("casual"); // the code is not shown when the list labels it
    expect(m).toContain(`data-testid="applicant-${L1}"`);
    expect(m).toContain(`data-testid="approve-${L2}"`);
  });
  it("bn: the Bangla type label where AKTCL supplied it, the English one where not", async () => {
    const t = text(await pageAs("dmo1", "dmo-pass-1", "DMO", "bn"));
    expect(t).toContain("আবেদনকারী");
    expect(t).toContain("নৈমিত্তিক");
    expect(t).toContain("Sick");
  });
  it("a TSO sees its own application read-only", async () => {
    const m = await pageAs("tso334", "tso-pass-1", "TSO");
    expect(text(m)).toContain("Rahim Uddin");
    expect(m).not.toContain("approve-");
  });
  it("when the code list cannot be read the code stands in, the page still renders", async () => {
    h.stub({ method: "GET", path: "/v1/admin/code-lists", fn: () => ({ status: 500, body: { type: "x", title: "x", status: 500, code: "ERR_INTERNAL" } }) });
    const t = text(await pageAs("dmo1", "dmo-pass-1", "DMO"));
    expect(t).toContain("casual");
    expect(t).toContain("Rahim Uddin");
  });
});

describe("the decision through the BFF", () => {
  const post = async (body: unknown) => leavePost(req("/api/bff/leave-decision", "POST", body, await signIn("dmo1", "dmo-pass-1")));
  it("a reject with a note reaches the API and is stored with the decision", async () => {
    const r = await post(decisionBody(L1, "reject", "Field audit that week"));
    expect(r.status).toBe(200);
    expect((await r.json()).status).toBe("rejected");
    expect(h.mock.state.dash.leaveNotes[L1]).toBe("Field audit that week");
  });
  it("a reject without a note is fine (the note is optional)", async () => {
    expect((await post(decisionBody(L2, "reject"))).status).toBe(200);
    expect(h.mock.state.dash.leaveNotes[L2]).toBeNull();
  });
  it("a note over the limit is refused before the API", async () => {
    expect((await post({ leave_uuid: L1, decision: "reject", note: "x".repeat(NOTE_MAX + 1) })).status).toBe(400);
    expect(h.mock.state.dash.leave.find((l) => l.leave_uuid === L1)?.status).toBe("pending");
  });
});
