// tso-manual W1, LV-19 (contract 1.10.0 LeaveApplication applicant_name, applicant_username, territory_name): what the deciding
// page shows of an application. The leave type is labelled from the `leave_type` code list (GET /v1/admin/code-lists, which the
// web roles may read); Bangla uses label_bn when AKTCL has supplied it, else the English label (the V0027 rule); a type the
// list does not know (or a failed read) shows its code. Pure helpers plus the one server read.
import type { Schemas } from "@/contract/types";
import { rawRequest } from "@/lib/api/raw";
import type { Locale } from "@/lib/i18n";

export type Leave = Schemas["LeaveApplication"];
type CodeItem = { code?: string | null; label_en?: string | null; label_bn?: string | null };
export type TypeLabels = Map<string, { en: string; bn: string | null }>;

/** The labels of the `leave_type` list; an empty map when the list cannot be read (the page then shows the codes). */
export async function loadLeaveTypes(token: string): Promise<TypeLabels> {
  const r = await rawRequest<{ lists?: { list_key: string; items: CodeItem[] }[] }>({ method: "GET", path: "/v1/admin/code-lists", token });
  const out: TypeLabels = new Map();
  if (!r.ok) return out;
  // Retired items stay in the map: an old application still shows the name it was filed under.
  for (const i of r.data.lists?.find((l) => l.list_key === "leave_type")?.items ?? []) if (i.code && i.label_en) out.set(i.code, { en: i.label_en, bn: i.label_bn ?? null });
  return out;
}

export function typeLabel(locale: Locale, labels: TypeLabels, code: string): string {
  const l = labels.get(code);
  if (!l) return code;
  return locale === "bn" ? (l.bn ?? l.en) : l.en;
}

/** The applicant as two lines: the full name, then the username; fallbacks when the server sends neither (a 1.9 server). */
export function applicantLines(l: Leave): { name: string; username: string | null } {
  const username = l.applicant_username ?? null;
  return { name: l.applicant_name ?? username ?? `#${l.user_id}`, username: l.applicant_name ? username : null };
}

/** DecisionRequest note limit: the contract's maxLength 500, which the BFF (/api/bff/leave-decision, after trimming) and LeaveApi
 *  both count in UTF-16 units (String.length). tso-manual X3 (R-W-13 (2)): was 300 at the proxy. */
export const NOTE_MAX = 500;

/** The body the BFF route takes: the note only on a rejection, trimmed, left out when empty. */
export function decisionBody(leaveUuid: string, decision: "approve" | "reject", note = ""): { leave_uuid: string; decision: "approve" | "reject"; note?: string } {
  const n = note.trim();
  return { leave_uuid: leaveUuid, decision, ...(decision === "reject" && n ? { note: n } : {}) };
}

export const noteTooLong = (note: string): boolean => note.trim().length > NOTE_MAX;
