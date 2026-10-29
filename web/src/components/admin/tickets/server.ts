// Server-side reads of the support console (TK-16, D-TSO-MAN-22): who is at the desk, the ticket list, the counts, one ticket with
// its thread, the photos' read URLs (TK-14) and the two code lists. Server only: the access token never reaches the browser.
import type { CodeList, Me, Role, SupportTicketDetail, SupportTicketPage, SupportTicketPriority, SupportTicketStatus, SupportTicketSummary } from "@/contract/types";
import type { ApiOutcome } from "@/lib/api/client";
import { rawRequest } from "@/lib/api/raw";
import { canOp } from "@/lib/admin/access";
import { accessOf, grantsOf } from "@/lib/admin/modules";
import { isCalendarDate } from "@/lib/dates";
import { isTicketPriority, isTicketStatus, SEED_RESOLUTION_CODE } from "./ticket-rules";

/** The caller at the desk: `desk` is Me.support_desk (absent means false); `canAct` adds the op roles and, for an ADMIN, a write grant
 *  on module `support` (a read grant shows the console without its actions). */
export interface DeskContext {
  desk: boolean;
  canAct: boolean;
}

export async function deskContext(token: string, role: Role): Promise<DeskContext> {
  const r = await rawRequest<Me>({ method: "GET", path: "/v1/me", token });
  if (!r.ok) return { desk: false, canAct: false };
  const desk = r.data.support_desk === true;
  const level = accessOf(grantsOf(role, r.data.modules, desk), "support");
  return { desk: desk && level !== "none", canAct: desk && level === "w" && canOp("ticket.update", role) && canOp("ticket.event", role) };
}

/** The list filters the console keeps in the URL; anything malformed is dropped, never sent. */
export interface TicketQuery {
  status?: SupportTicketStatus;
  q?: string;
  /** A search text of 1 character (the API needs 2 to 40): kept in the box, not sent. */
  qTooShort?: boolean;
  priority?: SupportTicketPriority;
  issue_type_code?: string;
  from?: string;
  to?: string;
  cursor?: string;
}

const one = (v: string | string[] | undefined): string | undefined => (Array.isArray(v) ? v[0] : v);

export function parseTicketQuery(sp: Record<string, string | string[] | undefined>): TicketQuery {
  const out: TicketQuery = {};
  const status = one(sp.status);
  if (isTicketStatus(status)) out.status = status;
  const q = one(sp.q)?.trim();
  if (q) {
    const n = Array.from(q).length;
    if (n >= 2 && n <= 40) out.q = q;
    else if (n === 1) out.qTooShort = true;
  }
  const priority = one(sp.priority);
  if (isTicketPriority(priority)) out.priority = priority;
  const issue = one(sp.issue_type_code);
  if (issue && /^[a-z][a-z0-9_]{1,40}$/.test(issue)) out.issue_type_code = issue;
  const from = one(sp.from);
  if (isCalendarDate(from)) out.from = from;
  const to = one(sp.to);
  if (isCalendarDate(to)) out.to = to;
  const cursor = one(sp.cursor);
  if (cursor && /^[A-Za-z0-9_-]{1,512}$/.test(cursor)) out.cursor = cursor;
  return out;
}

export const PAGE_SIZE = 50;

export function listTickets(token: string, q: TicketQuery): Promise<ApiOutcome<SupportTicketPage>> {
  return rawRequest<SupportTicketPage>({ method: "GET", path: "/v1/support/tickets", token, query: { status: q.status, q: q.q, priority: q.priority, issue_type_code: q.issue_type_code, from: q.from, to: q.to, cursor: q.cursor, limit: PAGE_SIZE } });
}

export function ticketSummary(token: string): Promise<ApiOutcome<SupportTicketSummary>> {
  return rawRequest<SupportTicketSummary>({ method: "GET", path: "/v1/support/tickets/summary", token });
}

export function getTicket(token: string, ticketUuid: string): Promise<ApiOutcome<SupportTicketDetail>> {
  return rawRequest<SupportTicketDetail>({ method: "GET", path: `/v1/support/tickets/${ticketUuid}`, token });
}

/** Short-lived read URLs of photos (TK-14: the desk reads `support_ticket*` media); a failed one is left out, never fatal. */
export async function photoUrls(token: string, ids: readonly string[]): Promise<Record<string, string>> {
  const unique = [...new Set(ids)];
  const urls = await Promise.all(unique.map(async (id) => {
    const r = await rawRequest<{ url: string }>({ method: "GET", path: `/v1/media/${id}/read-url`, token });
    return r.ok && typeof r.data.url === "string" ? ([id, r.data.url] as const) : null;
  }));
  return Object.fromEntries(urls.filter((u): u is readonly [string, string] => u !== null));
}

export interface CodeOption {
  code: string;
  label_en: string;
  label_bn: string | null;
}

/** Active items of one code list (valid on `today`), in sort order; null when the lists cannot be read (an ADMIN without
 *  the masterdata module, SUPPORT, or an outage): the console then offers what it can without them. */
export async function codeOptions(token: string, key: "ticket_resolution_code" | "ticket_issue_type", today: string): Promise<CodeOption[] | null> {
  const r = await rawRequest<{ lists: CodeList[] }>({ method: "GET", path: "/v1/admin/code-lists", token });
  if (!r.ok) return null;
  const list = r.data.lists.find((l) => l.list_key === key);
  if (!list) return null;
  return [...list.items]
    .filter((i) => (!i.valid_from || i.valid_from <= today) && (!i.valid_to || i.valid_to >= today))
    .sort((a, b) => a.sort - b.sort)
    .map((i) => ({ code: i.code, label_en: i.label_en, label_bn: i.label_bn ?? null }));
}

/** The resolution codes the resolve form offers: the list, or the V0131 seed alone when the list cannot be read. */
export async function resolutionCodes(token: string, today: string): Promise<{ options: CodeOption[]; fallback: boolean }> {
  const list = await codeOptions(token, "ticket_resolution_code", today);
  return list && list.length > 0 ? { options: list, fallback: false } : { options: [{ code: SEED_RESOLUTION_CODE, label_en: "Resolved", label_bn: null }], fallback: true };
}
