// CHECKER (TK-16 support console, TK-14 photos, D-TSO-MAN-20 to 25, R-F-11, R-F-14; build contract s8 "web support console"):
// resolve needs a code AND notes, cancel needs a reason, every desk action carries op_uuid and expected_version, a version
// conflict is shown with a reload, the thread names every desk entry "Support", the menu entry follows Me.support_desk, and
// the console's percentages are whole percent from the counts. The BFF runs against the contract mock (mock/tickets.ts).
import { describe, expect, it, vi } from "vitest";
import type { SupportTicketEvent, SupportTicketSummary } from "@/contract/types";
import { DeskPanel, TicketOpBanner } from "@/components/admin/tickets/desk-panel";
import { TicketListView } from "@/components/admin/tickets/ticket-list";
import { deskContext, parseTicketQuery } from "@/components/admin/tickets/server";
import { actorName, TicketThread } from "@/components/admin/tickets/ticket-thread";
import { cancelProblems, deskActions, keepsKey, outcomeOf, replyProblems, resolveProblems, updateBody } from "@/components/admin/tickets/ticket-rules";
import { ADMIN_MENU } from "@/app/admin/menu";
import { GRANT_MODULES, MODULE_KEYS } from "@/lib/admin/access-grants";
import { grantsOf, moduleOfApiPath, moduleOfPage } from "@/lib/admin/modules";
import { OPS } from "@/lib/admin/ops";
import { en } from "@/lib/i18n/messages-en";
import { bn } from "@/lib/i18n/messages-bn";
import { loadMenuAccess } from "@/lib/menu/matrix";
import { menuFor } from "@/lib/menu/menu";
import { admin, op, setupMock, support, token, tso } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }), usePathname: () => "/admin/tickets", redirect: () => { throw new Error("redirect"); }, notFound: () => { throw new Error("not found"); } }));

const h = setupMock();
const NEW = "7a1c0000-0000-4000-8000-000000000002"; // mock INC100002, status new, version 1
const RESOLVED = "7a1c0000-0000-4000-8000-000000000001"; // mock INC100001, status resolved, version 2
const OP1 = "11111111-1111-4111-8111-111111111111";
const OP2 = "22222222-2222-4222-8222-222222222222";
const OP3 = "33333333-3333-4333-8333-333333333333";

describe("D-TSO-MAN-20: resolve needs a code and notes, cancel needs a reason", () => {
  it("resolve without a code, without notes, or with notes over 2,000 characters is refused before sending", () => {
    expect(resolveProblems("", "Checked.")).toEqual({ resolution_code: "required" });
    expect(resolveProblems("resolved", "   ")).toEqual({ resolution_notes: "required" });
    expect(resolveProblems("", "")).toEqual({ resolution_code: "required", resolution_notes: "required" });
    expect(resolveProblems("resolved", "x".repeat(2001))).toEqual({ resolution_notes: "too_long" });
    expect(resolveProblems("resolved", "এ".repeat(2000))).toEqual({}); // characters, not bytes
  });
  it("cancel needs 3 to 500 characters of reason", () => {
    expect(cancelProblems("")).toEqual({ cancel_reason: "required" });
    expect(cancelProblems(" ab ")).toEqual({ cancel_reason: "too_short" });
    expect(cancelProblems("x".repeat(501))).toEqual({ cancel_reason: "too_long" });
    expect(cancelProblems("Duplicate of INC100002")).toEqual({});
  });
  it("a reply is 1 to 2,000 characters", () => {
    expect(replyProblems("  ")).toEqual({ body: "required" });
    expect(replyProblems("x".repeat(2001))).toEqual({ body: "too_long" });
    expect(replyProblems("Please restart the printer.")).toEqual({});
  });
  it("every update carries op_uuid and expected_version, and only the members of its change", () => {
    expect(updateBody(3, OP1, { kind: "resolve", code: " resolved ", notes: " Checked. " })).toEqual({ op_uuid: OP1, expected_version: 3, status: "resolved", resolution_code: "resolved", resolution_notes: "Checked." });
    expect(updateBody(1, OP1, { kind: "cancel", reason: "Test ticket" })).toEqual({ op_uuid: OP1, expected_version: 1, status: "cancelled", cancel_reason: "Test ticket" });
    expect(updateBody(1, OP1, { kind: "start" })).toEqual({ op_uuid: OP1, expected_version: 1, status: "in_progress" });
    expect(updateBody(2, OP1, { kind: "close" })).toEqual({ op_uuid: OP1, expected_version: 2, status: "closed" });
    expect(updateBody(1, OP1, { kind: "priority", priority: "p3" })).toEqual({ op_uuid: OP1, expected_version: 1, priority: "p3" });
  });
  it("the desk's buttons follow the transition matrix (no reopen, replies only while open)", () => {
    expect(deskActions("new")).toEqual(["reply", "start", "resolve", "cancel", "priority"]);
    expect(deskActions("in_progress")).toEqual(["reply", "resolve", "cancel", "priority"]);
    expect(deskActions("resolved")).toEqual(["close"]);
    expect(deskActions("closed")).toEqual([]);
    expect(deskActions("cancelled")).toEqual([]);
  });
  it("a lost answer or a 5xx keeps the idempotency key for the retry; any server answer settles it", () => {
    expect(keepsKey(null)).toBe(true);
    expect(keepsKey(503)).toBe(true);
    for (const s of [200, 201, 400, 403, 404, 409]) expect(keepsKey(s), String(s)).toBe(false);
  });
});

describe("the desk panel (rendered)", () => {
  const panel = (status: "new" | "in_progress" | "resolved" | "closed" | "cancelled", locale: "en" | "bn" = "en") =>
    html(<DeskPanel ticketUuid={NEW} version={4} status={status} priority="p1" codes={[{ code: "resolved", label: "Resolved" }]} codesFallback={false} />, locale);
  it("a new ticket offers reply, start, resolve (code and notes required, no code preselected), cancel (reason required) and priority", () => {
    const m = panel("new");
    for (const id of ["reply-form", "start-submit", "resolve-form", "cancel-form", "priority-submit"]) expect(m, id).toContain(`data-testid="${id}"`);
    expect(m).toMatch(/<select id="tk-resolve-code"[^>]*>\s*<option value="" selected="">/);
    expect(m).toMatch(/<label for="tk-resolve-code"[^>]*>[^<]*Resolution code<span aria-hidden="true"[^>]*>\*<\/span>/);
    expect(m).toMatch(/<label for="tk-resolve-notes"[^>]*>[^<]*Resolution notes<span aria-hidden="true"[^>]*>\*<\/span>/);
    expect(m).toMatch(/<label for="tk-cancel-reason"[^>]*>[^<]*Reason for cancelling<span aria-hidden="true"[^>]*>\*<\/span>/);
    expect(m).toContain('data-version="4"');
  });
  it("a resolved ticket offers only Close; closed and cancelled tickets offer nothing", () => {
    const r = panel("resolved");
    expect(r).toContain('data-testid="close-submit"');
    for (const id of ["reply-form", "resolve-form", "cancel-form", "start-submit"]) expect(r, id).not.toContain(`data-testid="${id}"`);
    for (const s of ["closed", "cancelled"] as const) expect(panel(s)).toContain('data-testid="desk-none"');
  });
  it("Bangla labels in bn", () => {
    expect(text(panel("new", "bn"))).toContain(bn["tickets.resolve.submit"]);
    expect(text(panel("new", "bn"))).toContain(bn["tickets.cancel.submit"]);
  });
});

describe("version conflict is shown (409 ERR_CONFLICT), with a reload", () => {
  it("the answer maps to a conflict, other refusals keep their problem text", () => {
    expect(outcomeOf(409, "ERR_CONFLICT")).toEqual({ kind: "conflict" });
    expect(outcomeOf(409, "ERR_TICKET_TRANSITION_INVALID")).toEqual({ kind: "refused", code: "ERR_TICKET_TRANSITION_INVALID" });
    expect(outcomeOf(null, undefined)).toEqual({ kind: "network" });
    expect(outcomeOf(200, undefined)).toEqual({ kind: "ok" });
  });
  it("the banner says the ticket changed and offers Reload (en and bn)", () => {
    const m = html(<TicketOpBanner action="resolve" outcome={{ kind: "conflict" }} />);
    expect(m).toContain('data-testid="op-conflict"');
    expect(m).toContain('data-testid="op-reload"');
    expect(text(m)).toContain(en["tickets.conflict"]);
    expect(text(html(<TicketOpBanner action="resolve" outcome={{ kind: "conflict" }} />, "bn"))).toContain(bn["tickets.conflict"]);
    expect(text(html(<TicketOpBanner action="reply" outcome={{ kind: "refused", code: "ERR_TICKET_COMMENTS_CLOSED" }} />))).toContain(en["error.ERR_TICKET_COMMENTS_CLOSED"]);
  });
});

describe("the desk ops through the BFF (contract mock)", () => {
  it("are whitelisted without a ChangeReason member, for the portal roles", () => {
    expect(OPS["ticket.update"]).toMatchObject({ method: "PATCH", path: "/v1/support/tickets/{ticket_uuid}", reason: null });
    expect(OPS["ticket.event"]).toMatchObject({ method: "POST", path: "/v1/support/tickets/{ticket_uuid}/events", reason: null });
  });
  it("resolve applies once: a replay of the same op answers 200 and writes nothing; a stale version is 409 ERR_CONFLICT", async () => {
    const c = await admin();
    const body = updateBody(1, OP1, { kind: "resolve", code: "resolved", notes: "Printer re-paired." });
    const r1 = await op(c, { op: "ticket.update", params: { ticket_uuid: NEW }, body });
    expect(r1.status).toBe(200);
    const t1 = ((await r1.json()) as { data: { status: string; version: number } }).data;
    expect(t1).toMatchObject({ status: "resolved", version: 2 });
    const events = () => h.mock.state.tickets.rows.find((r) => r.ticket.ticket_uuid === NEW)!.events;
    const n = events().length;
    expect(events().at(-1)).toMatchObject({ event_uuid: OP1, kind: "resolution", actor_is_support: true, body: "[TicketID: INC100002] - Resolved:\nResolution Code: Resolved\nResolution Notes:\n\nPrinter re-paired." });
    const replay = await op(c, { op: "ticket.update", params: { ticket_uuid: NEW }, body });
    expect(replay.status).toBe(200);
    expect(events().length).toBe(n);
    // another desk read version 1 before the resolve: its cancel is refused, nothing changes
    const stale = await op(c, { op: "ticket.update", params: { ticket_uuid: NEW }, body: updateBody(1, OP2, { kind: "cancel", reason: "Duplicate ticket" }) });
    expect(stale.status).toBe(409);
    const p = (await stale.json()) as { code: string };
    expect(p.code).toBe("ERR_CONFLICT");
    expect(outcomeOf(stale.status, p.code)).toEqual({ kind: "conflict" });
    expect(h.mock.state.tickets.rows.find((r) => r.ticket.ticket_uuid === NEW)!.ticket.status).toBe("resolved");
  });
  it("the same op_uuid with another body is 409 ERR_CONFLICT", async () => {
    const c = await admin();
    expect((await op(c, { op: "ticket.update", params: { ticket_uuid: NEW }, body: updateBody(1, OP3, { kind: "priority", priority: "p2" }) })).status).toBe(200);
    const again = await op(c, { op: "ticket.update", params: { ticket_uuid: NEW }, body: updateBody(2, OP3, { kind: "priority", priority: "p4" }) });
    expect(again.status).toBe(409);
    expect(((await again.json()) as { code: string }).code).toBe("ERR_CONFLICT");
  });
  it("a refused transition and a reply on a resolved ticket keep their own problem codes", async () => {
    const c = await admin();
    const close = await op(c, { op: "ticket.update", params: { ticket_uuid: NEW }, body: updateBody(1, OP1, { kind: "close" }) });
    expect(close.status).toBe(409);
    expect(((await close.json()) as { code: string }).code).toBe("ERR_TICKET_TRANSITION_INVALID");
    const reply = await op(c, { op: "ticket.event", params: { ticket_uuid: RESOLVED }, body: { event_uuid: OP2, body: "Any update?" } });
    expect(reply.status).toBe(409);
    expect(((await reply.json()) as { code: string }).code).toBe("ERR_TICKET_COMMENTS_CLOSED");
  });
  it("the first reply on a New ticket moves it to In Progress; a retried reply is stored once", async () => {
    const c = await admin();
    const body = { event_uuid: OP2, body: "We are checking the printer firmware." };
    expect((await op(c, { op: "ticket.event", params: { ticket_uuid: NEW }, body })).status).toBe(201);
    expect((await op(c, { op: "ticket.event", params: { ticket_uuid: NEW }, body })).status).toBe(200);
    const row = h.mock.state.tickets.rows.find((r) => r.ticket.ticket_uuid === NEW)!;
    expect(row.ticket.status).toBe("in_progress");
    expect(row.events.filter((e) => e.event_uuid === OP2)).toHaveLength(1);
  });
  it("a TSO cannot call a desk op (BFF), a SUPPORT user without the desk permission is refused by the API", async () => {
    expect((await op(await tso(), { op: "ticket.update", params: { ticket_uuid: NEW }, body: updateBody(1, OP1, { kind: "start" }) })).status).toBe(403);
    expect((await op(await support(), { op: "ticket.update", params: { ticket_uuid: NEW }, body: updateBody(1, OP1, { kind: "start" }) })).status).toBe(403);
  });
});

describe("D-TSO-MAN-21: the thread shows every desk entry as Support", () => {
  const ev = (over: Partial<SupportTicketEvent>): SupportTicketEvent => ({ event_uuid: OP1, kind: "comment", body: "x", actor_is_support: false, actor_display: "Rahim Uddin", from_status: null, to_status: null, resolution_code: null, created_at: "2026-10-28T10:00:00Z", attachments: [], ...over });
  it("a desk row is Support (never a name), a reporter row keeps the reporter's name", () => {
    expect(actorName("en", ev({ actor_is_support: true, actor_display: "Salma Akter" }))).toBe("Support");
    expect(actorName("bn", ev({ actor_is_support: true, actor_display: "Salma Akter" }))).toBe(bn["tickets.support"]);
    expect(actorName("en", ev({}))).toBe("Rahim Uddin");
    const m = html(<TicketThread locale="en" events={[ev({ event_uuid: OP2, actor_is_support: true, actor_display: "Salma Akter", body: "Checked." }), ev({})]} photos={{}} />);
    expect(m).not.toContain("Salma Akter");
    expect(m).toContain('data-support="1"');
    expect(text(m)).toContain("Support");
  });
  it("the resolution message keeps its line breaks; photos with a read URL are shown (TK-14)", () => {
    const m = html(<TicketThread locale="en" events={[ev({ kind: "resolution", actor_is_support: true, body: "[TicketID: INC100001] - Resolved:\nResolution Code: Resolved", attachments: [OP3] })]} photos={{ [OP3]: "https://blob.example/p.jpg" }} />);
    expect(m).toContain("whitespace-pre-wrap");
    expect(m).toContain('src="https://blob.example/p.jpg"');
  });
});

describe("the list: counts, tabs and whole percent from the counts (R-F-14)", () => {
  const summary: SupportTicketSummary = { as_of: "2026-10-29T04:00:00Z", total: 723, new: 0, in_progress: 0, resolved: 18, closed: 705, cancelled: 0, open: 0 };
  it("screenshot 11: Resolved 2% (18 of 723), Open 0%; tabs carry the counts", () => {
    const m = html(<TicketListView locale="en" query={{ status: "resolved" }} items={[]} nextCursor={null} summary={summary} issueTypes={null} nowMs={0} />);
    expect(m).toMatch(/data-testid="resolved-pct"[^>]*>2%</);
    expect(m).toMatch(/data-testid="open-pct"[^>]*>0%</);
    expect(text(m)).toContain("All (723)");
    expect(text(m)).toContain("Resolved (18)");
    expect(m).toMatch(/aria-current="page" data-testid="tab-resolved"/);
    const b = html(<TicketListView locale="bn" query={{}} items={[]} nextCursor={null} summary={{ ...summary, total: 201, resolved: 1, closed: 200 }} issueTypes={null} nowMs={0} />, "bn");
    expect(b).toMatch(/data-testid="resolved-pct"[^>]*>০%</); // 1 of 201 is 0%, never 1%
  });
  it("search needs 2 to 40 characters; malformed filters are dropped, never sent", () => {
    expect(parseTicketQuery({ q: "INC1001", status: "new", priority: "p1", from: "2026-10-01", cursor: "MTA" })).toEqual({ q: "INC1001", status: "new", priority: "p1", from: "2026-10-01", cursor: "MTA" });
    expect(parseTicketQuery({ q: "x" })).toEqual({ qTooShort: true });
    expect(parseTicketQuery({ q: "x".repeat(41), status: "open", priority: "p9", from: "2026-02-30", issue_type_code: "Bad Code", cursor: "a/b" })).toEqual({});
  });
});

describe("R-F-11 and D-TSO-MAN-22: module support and the Tickets menu entry", () => {
  it("support is the sixteenth grant module with bn and en labels; the desk routes map to it", () => {
    expect(GRANT_MODULES).toContain("support");
    expect(en[MODULE_KEYS.support]).toBe("Support tickets");
    expect(bn[MODULE_KEYS.support]).toMatch(/[ঀ-৿]/);
    expect(moduleOfApiPath(`/v1/support/tickets/${NEW}/events`)).toBe("support");
    expect(moduleOfApiPath("/v1/support/tickets")).toBe("support");
    expect(moduleOfApiPath("/v1/support/uploads")).toBe("mdm"); // the other support routes keep their module
    expect(moduleOfPage(`/admin/tickets/${NEW}`)).toBe("support");
  });
  it("the entry shows only with the desk permission (and, for an ADMIN, the module grant)", () => {
    const has = (role: "ADMIN" | "SUPERADMIN" | "SUPPORT", modules: Record<string, "r" | "w"> | null, desk: boolean) => menuFor(role, undefined, null, grantsOf(role, modules, desk)).flatMap((s) => s.items.map((i) => i.id)).includes("admin-tickets");
    expect(ADMIN_MENU.find((i) => i.id === "admin-tickets")).toMatchObject({ href: "/admin/tickets", labelKey: "tickets.menu" });
    expect(has("SUPERADMIN", null, true)).toBe(true);
    expect(has("SUPERADMIN", null, false)).toBe(false);
    expect(has("ADMIN", null, true)).toBe(true);
    expect(has("ADMIN", { support: "r" }, true)).toBe(true);
    expect(has("ADMIN", { outlets: "w" }, true)).toBe(false); // the desk without the module grant: the API refuses, so no entry
    expect(has("SUPPORT", null, false)).toBe(false);
    expect(has("SUPPORT", null, true)).toBe(true); // SUPPORT granted the permission on the permission page
    expect(menuFor("ADMIN", undefined, null, grantsOf("ADMIN", { outlets: "w" }, false)).flatMap((s) => s.items.map((i) => i.id))).toContain("admin-outlet-requests"); // other modules unchanged
  });
  it("the shell reads Me.support_desk: an ADMIN sees Tickets, a SUPPORT user without the permission does not", async () => {
    const ids = async (c: Record<string, string>, role: "ADMIN" | "SUPPORT") => menuFor(role, undefined, null, (await loadMenuAccess(token(c), role)).grants).flatMap((s) => s.items.map((i) => i.id));
    expect(await ids(await admin(), "ADMIN")).toContain("admin-tickets");
    expect(await ids(await support(), "SUPPORT")).not.toContain("admin-tickets");
  });
  it("the console itself refuses a caller without the desk; a desk ADMIN may act", async () => {
    expect(await deskContext(token(await admin()), "ADMIN")).toEqual({ desk: true, canAct: true });
    expect(await deskContext(token(await support()), "SUPPORT")).toEqual({ desk: false, canAct: false });
  });
});
