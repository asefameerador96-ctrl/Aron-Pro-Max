// The support desk's rules on the web (TK-16; D-TSO-MAN-20, 21; contract 1.10.0 updateSupportTicket and addSupportTicketEvent).
// Pure functions shared by the console's forms and its tests: which actions a ticket offers, what each form must hold before
// it is sent, the request body of each action, and how an answer is read. The API enforces every rule again; these only stop
// a request that would be refused and say why in the form.
import type { SupportTicketPriority, SupportTicketStatus, SupportTicketUpdate } from "@/contract/types";

export const TICKET_STATUSES = ["new", "in_progress", "resolved", "closed", "cancelled"] as const satisfies readonly SupportTicketStatus[];
export const TICKET_PRIORITIES = ["p1", "p2", "p3", "p4"] as const satisfies readonly SupportTicketPriority[];

/** Bounds of the contract (SupportTicketUpdate, SupportTicketEventWrite), in characters (code points, as JSON Schema counts). */
export const NOTES_MAX = 2000;
export const REPLY_MAX = 2000;
export const CANCEL_MIN = 3;
export const CANCEL_MAX = 500;
/** The `ticket_resolution_code` item V0131 seeds (D-TSO-MAN-20): offered alone when the code list cannot be read. */
export const SEED_RESOLUTION_CODE = "resolved";

/** A client UUID v4 exactly as the contract's `Uuid` pattern (lower case). */
export const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export const isTicketStatus = (v: unknown): v is SupportTicketStatus => typeof v === "string" && (TICKET_STATUSES as readonly string[]).includes(v);
export const isTicketPriority = (v: unknown): v is SupportTicketPriority => typeof v === "string" && (TICKET_PRIORITIES as readonly string[]).includes(v);

/** Open = new + in_progress: the only statuses that take comments and replies (D-TSO-MAN-21). */
export const isOpen = (s: SupportTicketStatus): boolean => s === "new" || s === "in_progress";

export type DeskAction = "reply" | "start" | "resolve" | "cancel" | "close" | "priority";

/**
 * The D-TSO-MAN-20 matrix as the desk's buttons: new -> in_progress (Start, or the first reply), new or in_progress -> resolved
 * or cancelled, resolved -> closed. Replies only while open (D-TSO-MAN-21). No reopen: a closed or cancelled ticket offers nothing.
 * The priority is changed on open tickets only (the smallest correct reading of "support may change the priority").
 */
export function deskActions(status: SupportTicketStatus): DeskAction[] {
  switch (status) {
    case "new":
      return ["reply", "start", "resolve", "cancel", "priority"];
    case "in_progress":
      return ["reply", "resolve", "cancel", "priority"];
    case "resolved":
      return ["close"];
    default:
      return [];
  }
}

export type FieldCode = "required" | "too_short" | "too_long";
const chars = (s: string): number => Array.from(s).length;

/** A resolve needs a resolution code and notes of 1 to 2,000 characters. */
export function resolveProblems(code: string, notes: string): Partial<Record<"resolution_code" | "resolution_notes", FieldCode>> {
  const out: Partial<Record<"resolution_code" | "resolution_notes", FieldCode>> = {};
  if (code.trim() === "") out.resolution_code = "required";
  const n = chars(notes.trim());
  if (n === 0) out.resolution_notes = "required";
  else if (n > NOTES_MAX) out.resolution_notes = "too_long";
  return out;
}

/** A cancel needs a reason of 3 to 500 characters. */
export function cancelProblems(reason: string): Partial<Record<"cancel_reason", FieldCode>> {
  const n = chars(reason.trim());
  if (n === 0) return { cancel_reason: "required" };
  if (n < CANCEL_MIN) return { cancel_reason: "too_short" };
  if (n > CANCEL_MAX) return { cancel_reason: "too_long" };
  return {};
}

/** A reply is 1 to 2,000 characters. */
export function replyProblems(body: string): Partial<Record<"body", FieldCode>> {
  const n = chars(body.trim());
  if (n === 0) return { body: "required" };
  if (n > REPLY_MAX) return { body: "too_long" };
  return {};
}

export type TicketChange =
  | { kind: "start" }
  | { kind: "resolve"; code: string; notes: string }
  | { kind: "cancel"; reason: string }
  | { kind: "close" }
  | { kind: "priority"; priority: SupportTicketPriority };

/** The PATCH body of one desk action: always `op_uuid` (the action's idempotency key) and `expected_version` (the version read). */
export function updateBody(version: number, opUuid: string, change: TicketChange): SupportTicketUpdate {
  const base = { op_uuid: opUuid, expected_version: version };
  switch (change.kind) {
    case "start":
      return { ...base, status: "in_progress" };
    case "resolve":
      return { ...base, status: "resolved", resolution_code: change.code.trim(), resolution_notes: change.notes.trim() };
    case "cancel":
      return { ...base, status: "cancelled", cancel_reason: change.reason.trim() };
    case "close":
      return { ...base, status: "closed" };
    case "priority":
      return { ...base, priority: change.priority };
  }
}

/** How the desk reads an answer. `conflict` is 409 ERR_CONFLICT: the ticket changed since it was read, so the desk reloads
 *  before trying again; `reloaded` is the same 409 on a resend of an unanswered request with its kept key (our own first send may
 *  have landed, so it is not "someone else"): the page is read again; `refused` carries the problem code; `network` = no answer. */
export type TicketOutcome = { kind: "ok" } | { kind: "conflict" } | { kind: "reloaded" } | { kind: "refused"; code: string | undefined } | { kind: "network" };

export function outcomeOf(status: number | null, code: string | undefined, resent = false): TicketOutcome {
  if (status === null) return { kind: "network" };
  if (status >= 200 && status < 300) return { kind: "ok" };
  if (status === 409 && code === "ERR_CONFLICT") return resent ? { kind: "reloaded" } : { kind: "conflict" };
  return { kind: "refused", code };
}

/**
 * Whether an action keeps its idempotency key for the next attempt. No answer or a 5xx: the server may have applied it, so the
 * retry must replay with the same key (a replay answers 200 and writes nothing). Any other answer settles the attempt: a success
 * or a refusal (nothing applied) starts a fresh key, so the next action is a new op.
 */
export const keepsKey = (status: number | null): boolean => status === null || status >= 500;

/** JSON with the object keys sorted at every level (undefined members left out): the same request always gives the same text. */
export function canonicalJson(v: unknown): string {
  if (Array.isArray(v)) return `[${v.map(canonicalJson).join(",")}]`;
  if (v !== null && typeof v === "object") {
    const o = v as Record<string, unknown>;
    return `{${Object.keys(o).sort().filter((k) => o[k] !== undefined).map((k) => `${JSON.stringify(k)}:${canonicalJson(o[k])}`).join(",")}}`;
  }
  return JSON.stringify(v) ?? "null";
}

/** What identifies a desk request apart from its idempotency key: the canonical body without `op_uuid` and `event_uuid`. */
export function requestSignature(body: object): string {
  const rest: Record<string, unknown> = { ...(body as Record<string, unknown>) };
  delete rest.op_uuid;
  delete rest.event_uuid;
  return canonicalJson(rest);
}

/** What an action's last attempt left behind: its key, the request it was for, and whether the server answered it. */
export interface KeyedAttempt {
  key: string;
  signature: string;
  answered: boolean;
}

/** The key of the next attempt: the last one again only when it got no answer AND the request is unchanged (a resend, which
 *  the server replays); any answer, or an edited reply, note, reason or priority, is a new request with a new key. */
export function nextKey(last: KeyedAttempt | undefined, signature: string, fresh: () => string): { key: string; resent: boolean } {
  return last && !last.answered && last.signature === signature ? { key: last.key, resent: true } : { key: fresh(), resent: false };
}

/** The attempt record after an answer (status null = no answer at all). */
export const settleAttempt = (key: string, signature: string, status: number | null): KeyedAttempt => ({ key, signature, answered: !keepsKey(status) });
