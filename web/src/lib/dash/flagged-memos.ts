// Flagged memos page (D-WEB-FLAGGED-MEMOS-01): the query of GET /v1/dashboards/flagged-memos built from the URL, and the typed read.
// Server only for the read. Scope (geography) is cut by the server from the token; the page sends only a node the picker offered.
import type { Role, Schemas } from "@/contract/types";
import { rawRequest } from "@/lib/api/raw";
import type { ApiOutcome } from "@/lib/api/client";
import { isCalendarDate } from "@/lib/dates";

export type FlaggedMemoPage = Schemas["FlaggedMemoPage"];
export type FlaggedMemo = Schemas["FlaggedMemoView"];

/** Portal levels that open the page (docs/34 s1; the server decides again): BRAND has no memo dimension, SR and AMO no portal login. */
export const FLAGGED_MEMO_ROLES = ["SUPERADMIN", "ADMIN", "NATIONAL", "WM", "DMO", "TSO"] as const satisfies readonly Role[];

/** Flags documented in contract 1.8.0 (`MemoServerFlag`); the field is a string, so any other value is shown as given. */
export const KNOWN_FLAGS = ["zero_price_line", "price_mismatch", "arithmetic_mismatch", "pack_factor_mismatch"] as const;

/** The contract's range cap: `to` is at most 92 days after `from`. */
export const MAX_RANGE_DAYS = 92;
export const PAGE_SIZE = 50;

export interface FlaggedQuery {
  from: string;
  to: string;
  flag: string | undefined;
  cursor: string | undefined;
  /** Set when the range cannot be asked: the page says so instead of sending a request the server would refuse. */
  rangeError: "order" | "too_long" | null;
}

/** Whole days from one business date to a later one (UTC arithmetic on the date strings: Dhaka has no DST). */
export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}

const first = (v: string | string[] | undefined): string | undefined => (Array.isArray(v) ? v[0] : v);

export function parseFlaggedQuery(sp: Record<string, string | string[] | undefined>, today: string): FlaggedQuery {
  const rawFrom = first(sp.from);
  const rawTo = first(sp.to);
  // Both default to today, as the contract does: a lone `from` in the past asks for "from then until today".
  const from = isCalendarDate(rawFrom) ? rawFrom : today;
  const to = isCalendarDate(rawTo) ? rawTo : today;
  const flag = first(sp.flag)?.trim().slice(0, 60) || undefined;
  const cursor = first(sp.cursor)?.slice(0, 512) || undefined;
  const days = daysBetween(from, to);
  return { from, to, flag, cursor, rangeError: days < 0 ? "order" : days > MAX_RANGE_DAYS ? "too_long" : null };
}

/** The URL of the page for a query and node (the paging and reset links keep the filters; `cursor` is the page). */
export function flaggedHref(q: Pick<FlaggedQuery, "from" | "to" | "flag">, geo: Record<string, string | undefined>, cursor?: string): string {
  const p = new URLSearchParams({ from: q.from, to: q.to });
  if (q.flag) p.set("flag", q.flag);
  for (const [k, v] of Object.entries(geo)) if (v) p.set(k, v);
  if (cursor) p.set("cursor", cursor);
  return `/flagged-memos?${p.toString()}`;
}

export const getFlaggedMemos = (token: string, q: { from: string; to: string; flag?: string; level?: string; node_id?: number; cursor?: string }): Promise<ApiOutcome<FlaggedMemoPage>> =>
  rawRequest<FlaggedMemoPage>({ method: "GET", path: "/v1/dashboards/flagged-memos", token, query: { from: q.from, to: q.to, flag: q.flag, level: q.level, node_id: q.node_id, cursor: q.cursor, limit: PAGE_SIZE } });
