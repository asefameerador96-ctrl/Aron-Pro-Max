// Mock of the support desk (contract 1.10.0: listSupportTickets, getSupportTicketSummary, getSupportTicket, updateSupportTicket,
// addSupportTicketEvent; D-TSO-MAN-18 to 25). In memory; reset with /__mock/reset. It follows the contract's rules so the console
// can be driven end to end: the reporter sees own tickets, the desk (Me.support_desk) every ticket; a desk op is idempotent by
// `op_uuid` (same body replays 200 and writes nothing, another body is 409 ERR_CONFLICT), `expected_version` must match (else
// 409 ERR_CONFLICT), the D-TSO-MAN-20 matrix (else 409 ERR_TICKET_TRANSITION_INVALID), replies only while open (409
// ERR_TICKET_COMMENTS_CLOSED) and the first reply on a New ticket moves it to In Progress.
import { createHash } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Role, SupportTicket, SupportTicketEvent, SupportTicketStatus } from "../src/contract/types";
import type { Ctx } from "./tables";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const STATUSES: readonly SupportTicketStatus[] = ["new", "in_progress", "resolved", "closed", "cancelled"];

/** The 31 Annex A issue types (db V0131), code -> English label; label_bn stays null until AKTCL supplies it. */
export const ISSUE_TYPES: readonly (readonly [string, string])[] = [
  ["app_crash_order_submit", "App crashes on order submit"], ["app_version_update", "Application Version Update"], ["astha_gift_disbursement", "Astha Gift Choice & Disbursement"],
  ["astha_target", "Astha Target"], ["auto_logout", "Auto Logout"], ["camera_black_screen", "Camera Black Screen"], ["campaign", "Campaign"], ["change_request", "Change Request"],
  ["check_in_out", "Check in Check out issue"], ["cluster_mapping", "Cluster Mapping"], ["discount", "Discount"], ["ff_geo_location_map", "FF Geo Location Map"],
  ["final_submission", "Final Submission"], ["gps_inaccuracy", "GPS location inaccuracy"], ["login_otp", "Login OTP issue"], ["memo_printing", "Memo Printing issue"],
  ["new_application_needed", "New Application Needed (Tso, Amo, SR)"], ["others", "Others"], ["outlet_related", "Outlet Related Issue"], ["outlet_request", "Outlet request (Close, New, & Info Update)"],
  ["outlet_request_approval", "Outlet Request Approval"], ["password_reset", "Password Reset"], ["printer_connection", "Printer Connection issue"], ["reporting", "Reporting"],
  ["sales_adjustment", "Sales Adjustment"], ["sales_order_failing", "Sales order creation failing"], ["sales_submit", "Sales Submit (Bikroy Joma)"], ["sync_stuck", "Sync stuck / not updating"],
  ["target_panel", "Target Panel"], ["user_login", "User Login issue"], ["web_entry", "Web Entry"],
];
const ISSUE = new Map(ISSUE_TYPES);
const RESOLUTION = new Map([["resolved", "Resolved"]]);

interface Row {
  ticket: SupportTicket;
  events: SupportTicketEvent[];
  attachments: string[];
  /** op_uuid -> fingerprint of the body (R-F-03): a replay with the same body writes nothing. */
  ops: Map<string, string>;
}
export interface TicketStore {
  rows: Row[];
  /** Every event uuid in use, so an op_uuid or event_uuid reused by another ticket or event is a conflict. */
  eventUuids: Set<string>;
  nextNo: number;
}

const REPORTER = { user_id: 2001, username: "tso334", full_name: "Rahim Uddin", role: "TSO" as Role };
const DAY = 86_400_000;

function uuidOf(n: number): string {
  return `7a1c0000-0000-4000-8000-${String(n).padStart(12, "0")}`;
}
const ago = (ms: number) => new Date(Date.now() - ms).toISOString();
const dhakaDate = (iso: string) => new Date(Date.parse(iso) + 6 * 3_600_000).toISOString().slice(0, 10);
export const resolutionMessage = (no: string, label: string, notes: string) => `[TicketID: ${no}] - Resolved:\nResolution Code: ${label}\nResolution Notes:\n\n${notes}`;

function seedRow(n: number, issue: string, title: string, status: SupportTicketStatus, ageMs: number, extra: Partial<SupportTicket> = {}, events: Partial<SupportTicketEvent>[] = []): Row {
  const created = ago(ageMs);
  const no = `INC${100000 + n}`;
  const ticket: SupportTicket = {
    ticket_uuid: uuidOf(n), ticket_no: no, issue_type_code: issue, issue_type_label_en: ISSUE.get(issue) ?? issue, issue_type_label_bn: null, category_label_en: "Tickets",
    title, description: "", source: "quick", priority: "p1", status, requester: REPORTER, affected_username: null, assignee_user_id: null, assignee_name: null,
    business_date: dhakaDate(created), captured_at: created, created_at: created, updated_at: created, first_response_at: null, resolved_at: null, closed_at: null, cancelled_at: null,
    resolution_code: null, resolution_label_en: null, resolution_notes: null, cancel_reason: null, version: 1, attachment_count: 0, ...extra,
  };
  const ev = (i: number, e: Partial<SupportTicketEvent>): SupportTicketEvent => ({ event_uuid: uuidOf(n * 100 + i), kind: "comment", body: null, actor_is_support: false, actor_display: REPORTER.full_name, from_status: null, to_status: null, resolution_code: null, created_at: created, attachments: [], ...e });
  return { ticket, events: [ev(0, { kind: "created", to_status: "new" }), ...events.map((e, i) => ev(i + 1, e))], attachments: [], ops: new Map() };
}

export function freshTicketStore(): TicketStore {
  const resolvedAt = ago(4 * DAY - 3_600_000);
  const rows = [
    seedRow(1, "login_otp", "Login OTP issue", "resolved", 4 * DAY, { first_response_at: resolvedAt, resolved_at: resolvedAt, resolution_code: "resolved", resolution_label_en: "Resolved", resolution_notes: "Checked.", version: 2, updated_at: resolvedAt }, [
      { kind: "resolution", body: resolutionMessage("INC100001", "Resolved", "Checked."), actor_is_support: true, actor_display: "Support", from_status: "new", to_status: "resolved", resolution_code: "resolved", created_at: resolvedAt },
    ]),
    seedRow(2, "printer_connection", "Printer does not pair after the update", "new", 2 * 3_600_000, { description: "MP-58N shows paired but nothing prints since this morning.", source: "manual", affected_username: "sr334001" }),
    seedRow(3, "sync_stuck", "Sync stuck since 10 am", "in_progress", 26 * 3_600_000, { first_response_at: ago(25 * 3_600_000), version: 2, updated_at: ago(25 * 3_600_000) }, [
      { kind: "comment", body: "Please keep the phone on Wi-Fi for ten minutes, then pull to refresh.", actor_is_support: true, actor_display: "Support", created_at: ago(25 * 3_600_000) },
      { kind: "comment", body: "Still stuck at 80%.", created_at: ago(24 * 3_600_000) },
    ]),
    seedRow(4, "final_submission", "Final Submission", "closed", 11 * DAY, { resolution_code: "resolved", resolution_label_en: "Resolved", resolution_notes: "Zone reopened and submitted.", resolved_at: ago(10 * DAY), closed_at: ago(10 * DAY - 3_600_000), first_response_at: ago(10 * DAY), version: 3, updated_at: ago(10 * DAY - 3_600_000) }),
    seedRow(5, "astha_target", "test", "cancelled", 38 * DAY, { cancelled_at: ago(37 * DAY), cancel_reason: "Test ticket", version: 2, updated_at: ago(37 * DAY) }),
  ];
  return { rows, eventUuids: new Set(rows.flatMap((r) => r.events.map((e) => e.event_uuid))), nextNo: 6 };
}

/** Canonical JSON (sorted members), as the server compares an op's body (R-F-03). */
function canonical(v: unknown): string {
  if (Array.isArray(v)) return `[${v.map(canonical).join(",")}]`;
  if (v && typeof v === "object") return `{${Object.keys(v).sort().map((k) => `${JSON.stringify(k)}:${canonical((v as Record<string, unknown>)[k])}`).join(",")}}`;
  return JSON.stringify(v);
}
const fingerprint = (ticketUuid: string, body: unknown) => createHash("sha256").update(`${ticketUuid}|${canonical(body)}`).digest("hex");
const stableV4 = (name: string) => {
  const h = createHash("sha256").update(name, "utf8").digest();
  h[6] = (h[6]! & 0x0f) | 0x40;
  h[8] = (h[8]! & 0x3f) | 0x80;
  const x = h.subarray(0, 16).toString("hex");
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`;
};
const chars = (s: unknown) => (typeof s === "string" ? Array.from(s).length : -1);

export interface TicketCaller {
  user_id: number;
  role: Role;
  desk: boolean;
}

/** Answers /v1/support/tickets/**; false for any other path. */
export async function handleTickets(s: TicketStore, ctx: Ctx, me: TicketCaller, method: string, url: URL, req: IncomingMessage, res: ServerResponse): Promise<boolean> {
  const path = url.pathname;
  if (!path.startsWith("/v1/support/tickets")) return false;
  const { send, problem } = ctx;
  const done = (status: number, body: unknown): true => {
    send(res, status, body);
    return true;
  };
  const visible = (r: Row) => me.desk || r.ticket.requester.user_id === me.user_id;
  const view = (tk: SupportTicket): SupportTicket => (me.desk ? tk : (({ assignee_user_id: _a, assignee_name: _n, ...rest }) => rest)(tk));

  if (path === "/v1/support/tickets/summary" && method === "GET") {
    const mine = s.rows.filter(visible).map((r) => r.ticket.status);
    const n = (st: SupportTicketStatus) => mine.filter((x) => x === st).length;
    send(res, 200, { as_of: new Date().toISOString(), total: mine.length, new: n("new"), in_progress: n("in_progress"), resolved: n("resolved"), closed: n("closed"), cancelled: n("cancelled"), open: n("new") + n("in_progress") });
    return true;
  }

  if (path === "/v1/support/tickets" && method === "GET") {
    const p = url.searchParams;
    const errors: { pointer: string; code: string }[] = [];
    const q = p.get("q");
    if (q !== null && (chars(q) < 2 || chars(q) > 40)) errors.push({ pointer: "/q", code: "invalid" });
    const status = p.get("status");
    if (status !== null && !STATUSES.includes(status as SupportTicketStatus)) errors.push({ pointer: "/status", code: "invalid" });
    const limit = Number(p.get("limit") ?? 50);
    if (!Number.isInteger(limit) || limit < 1 || limit > 200) errors.push({ pointer: "/limit", code: "invalid" });
    if (!me.desk && (p.has("requester_user_id") || p.has("assignee_user_id"))) {
      send(res, 403, problem(403, "ERR_FORBIDDEN"));
      return true;
    }
    if (errors.length) {
      send(res, 400, problem(400, "ERR_VALIDATION", { errors }));
      return true;
    }
    const needle = q?.toLowerCase();
    const rows = s.rows
      .filter(visible)
      .map((r) => r.ticket)
      .filter((tk) => !status || tk.status === status)
      .filter((tk) => !p.get("priority") || tk.priority === p.get("priority"))
      .filter((tk) => !p.get("issue_type_code") || tk.issue_type_code === p.get("issue_type_code"))
      .filter((tk) => !p.get("from") || tk.business_date >= p.get("from")!)
      .filter((tk) => !p.get("to") || tk.business_date <= p.get("to")!)
      .filter((tk) => !needle || (tk.ticket_no ?? "").toLowerCase().startsWith(needle) || tk.title.toLowerCase().includes(needle) || tk.issue_type_label_en.toLowerCase().includes(needle))
      .sort((a, b) => b.created_at.localeCompare(a.created_at) || b.ticket_uuid.localeCompare(a.ticket_uuid));
    const offset = Number(Buffer.from(p.get("cursor") ?? "MA", "base64url").toString() || "0") || 0;
    const page = rows.slice(offset, offset + limit);
    send(res, 200, { items: page.map(view), next_cursor: offset + limit < rows.length ? Buffer.from(String(offset + limit)).toString("base64url") : null });
    return true;
  }

  const m = /^\/v1\/support\/tickets\/([^/]+)(\/events)?$/.exec(path);
  if (!m) return false;
  const row = s.rows.find((r) => r.ticket.ticket_uuid === m[1]);

  if (!m[2] && method === "GET") {
    if (!row || !visible(row)) send(res, 404, problem(404, "ERR_NOT_FOUND"));
    else send(res, 200, { ticket: view(row.ticket), events: row.events, attachments: row.attachments });
    return true;
  }

  if (!m[2] && method === "PATCH") {
    if (!me.desk) return done(403, problem(403, "ERR_FORBIDDEN"));
    const b = (await ctx.readJson(req)) as Record<string, unknown> | undefined;
    const allowed = ["op_uuid", "expected_version", "status", "priority", "assignee_user_id", "resolution_code", "resolution_notes", "cancel_reason"];
    const errors: { pointer: string; code: string }[] = [];
    if (!b || typeof b !== "object" || Array.isArray(b)) return done(400, problem(400, "ERR_MALFORMED_JSON"));
    for (const k of Object.keys(b)) if (!allowed.includes(k)) errors.push({ pointer: `/${k}`, code: "unknown_member" });
    if (typeof b.op_uuid !== "string" || !UUID.test(b.op_uuid)) errors.push({ pointer: "/op_uuid", code: "invalid" });
    if (typeof b.expected_version !== "number" || !Number.isInteger(b.expected_version) || b.expected_version < 1) errors.push({ pointer: "/expected_version", code: "invalid" });
    if (b.status !== undefined && !["in_progress", "resolved", "closed", "cancelled"].includes(String(b.status))) errors.push({ pointer: "/status", code: "invalid" });
    if (b.priority !== undefined && !["p1", "p2", "p3", "p4"].includes(String(b.priority))) errors.push({ pointer: "/priority", code: "invalid" });
    if (b.resolution_notes !== undefined && (chars(b.resolution_notes) < 1 || chars(b.resolution_notes) > 2000)) errors.push({ pointer: "/resolution_notes", code: "invalid" });
    if (b.cancel_reason !== undefined && (chars(b.cancel_reason) < 3 || chars(b.cancel_reason) > 500)) errors.push({ pointer: "/cancel_reason", code: "invalid" });
    if (b.status === "resolved" && (typeof b.resolution_code !== "string" || !RESOLUTION.has(b.resolution_code))) errors.push({ pointer: "/resolution_code", code: "required" });
    if (b.status === "resolved" && b.resolution_notes === undefined) errors.push({ pointer: "/resolution_notes", code: "required" });
    if (b.status === "cancelled" && b.cancel_reason === undefined) errors.push({ pointer: "/cancel_reason", code: "required" });
    if (errors.length) return done(400, problem(400, "ERR_VALIDATION", { errors }));
    if (!row) return done(404, problem(404, "ERR_NOT_FOUND"));
    const op = b.op_uuid as string;
    const fp = fingerprint(row.ticket.ticket_uuid, b);
    const seen = row.ops.get(op);
    if (seen !== undefined) return done(seen === fp ? 200 : 409, seen === fp ? view(row.ticket) : problem(409, "ERR_CONFLICT"));
    if (s.eventUuids.has(op)) return done(409, problem(409, "ERR_CONFLICT"));
    const tk = row.ticket;
    if (b.expected_version !== tk.version) return done(409, problem(409, "ERR_CONFLICT", { detail: `version is ${tk.version}` }));
    const to = b.status as SupportTicketStatus | undefined;
    if (to !== undefined) {
      const ok = (to === "in_progress" && tk.status === "new") || ((to === "resolved" || to === "cancelled") && (tk.status === "new" || tk.status === "in_progress")) || (to === "closed" && tk.status === "resolved");
      if (!ok) return done(409, problem(409, "ERR_TICKET_TRANSITION_INVALID"));
    }
    const now = new Date().toISOString();
    const kinds: SupportTicketEvent[] = [];
    const evOf = (kind: SupportTicketEvent["kind"], over: Partial<SupportTicketEvent>): SupportTicketEvent => ({ event_uuid: kinds.length === 0 ? op : stableV4(`aron.support.ticket-op:${op}:${kind}`), kind, body: null, actor_is_support: true, actor_display: "Support", from_status: null, to_status: null, resolution_code: null, created_at: now, attachments: [], ...over });
    if (to !== undefined) {
      const from = tk.status;
      if (to === "resolved") {
        const label = RESOLUTION.get(b.resolution_code as string)!;
        Object.assign(tk, { resolution_code: b.resolution_code, resolution_label_en: label, resolution_notes: b.resolution_notes, resolved_at: now });
        kinds.push(evOf("resolution", { body: resolutionMessage(tk.ticket_no ?? "", label, b.resolution_notes as string), from_status: from, to_status: to, resolution_code: b.resolution_code as string }));
      } else {
        if (to === "cancelled") Object.assign(tk, { cancel_reason: b.cancel_reason, cancelled_at: now });
        if (to === "closed") tk.closed_at = now;
        kinds.push(evOf("status_change", { from_status: from, to_status: to }));
      }
      tk.status = to;
      tk.first_response_at ??= now;
    }
    if (b.priority !== undefined && b.priority !== tk.priority) {
      tk.priority = b.priority as SupportTicket["priority"];
      kinds.push(evOf("priority_change", {}));
    }
    if (kinds.length === 0) return done(200, view(tk)); // no change: nothing recorded, the op_uuid stays unused
    tk.version += 1;
    tk.updated_at = now;
    row.events.push(...kinds);
    for (const e of kinds) s.eventUuids.add(e.event_uuid);
    row.ops.set(op, fp);
    ctx.audit("support_ticket", tk.ticket_uuid, "update", {}, { status: tk.status, priority: tk.priority, version: tk.version }, null);
    send(res, 200, view(tk));
    return true;
  }

  if (m[2] && method === "POST") {
    if (!me.desk) return done(403, problem(403, "ERR_FORBIDDEN"));
    const b = (await ctx.readJson(req)) as Record<string, unknown> | undefined;
    if (!b || typeof b !== "object" || Array.isArray(b)) return done(400, problem(400, "ERR_MALFORMED_JSON"));
    const errors: { pointer: string; code: string }[] = [];
    for (const k of Object.keys(b)) if (k !== "event_uuid" && k !== "body") errors.push({ pointer: `/${k}`, code: "unknown_member" });
    if (typeof b.event_uuid !== "string" || !UUID.test(b.event_uuid)) errors.push({ pointer: "/event_uuid", code: "invalid" });
    if (chars(b.body) < 1 || chars(b.body) > 2000) errors.push({ pointer: "/body", code: "invalid" });
    if (errors.length) return done(400, problem(400, "ERR_VALIDATION", { errors }));
    if (!row) return done(404, problem(404, "ERR_NOT_FOUND"));
    const stored = row.events.find((e) => e.event_uuid === b.event_uuid);
    if (stored) {
      const same = stored.kind === "comment" && stored.actor_is_support && stored.body === b.body;
      return done(same ? 200 : 409, same ? stored : problem(409, "ERR_CONFLICT"));
    }
    if (s.eventUuids.has(b.event_uuid as string)) return done(409, problem(409, "ERR_CONFLICT"));
    const tk = row.ticket;
    if (tk.status !== "new" && tk.status !== "in_progress") return done(409, problem(409, "ERR_TICKET_COMMENTS_CLOSED"));
    const now = new Date().toISOString();
    const ev: SupportTicketEvent = { event_uuid: b.event_uuid as string, kind: "comment", body: b.body as string, actor_is_support: true, actor_display: "Support", from_status: null, to_status: null, resolution_code: null, created_at: now, attachments: [] };
    row.events.push(ev);
    s.eventUuids.add(ev.event_uuid);
    if (tk.status === "new") {
      tk.status = "in_progress";
      tk.version += 1;
    }
    tk.first_response_at ??= now;
    tk.updated_at = now;
    send(res, 201, ev);
    return true;
  }

  return done(404, problem(404, "ERR_NOT_FOUND"));
}
