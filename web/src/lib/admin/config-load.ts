// Server-side loaders of the configuration pages: one place that knows which contract operation feeds which widget.
import type { ApiOutcome } from "@/lib/api/client";
import { rawRequest } from "@/lib/api/raw";
import type { ConfigChange, ConfigChangePage, ResolvedConfigValue, ConfigKey, ConfigReach, ConfigVersion, ConfigVersionDetail, ConfigVersionPage } from "./types";

const get = <T>(path: string, token: string, query?: Record<string, string | number | undefined>): Promise<ApiOutcome<T>> => rawRequest<T>({ method: "GET", path, token, query });

export const listKeys = (token: string, area?: string) => get<{ items: ConfigKey[] }>("/v1/admin/config/keys", token, { area });
export const latestVersion = async (token: string): Promise<ApiOutcome<ConfigVersion | null>> => {
  const r = await get<ConfigVersionPage>("/v1/admin/config/versions", token, { limit: 1 });
  return r.ok ? { ...r, data: r.data.items[0] ?? null } : r;
};
export const versionDetail = (token: string, version: number) => get<ConfigVersionDetail>(`/v1/admin/config/versions/${version}`, token);
export const reachOf = (token: string, version: number, zone?: string) => get<ConfigReach>(`/v1/admin/config/reach/${version}`, token, { zone_id: zone });
export const changesOf = (token: string, q: { status?: string; key?: string; limit?: number; cursor?: string }) => get<ConfigChangePage>("/v1/admin/config/changes", token, q);

export interface PendingCount {
  count: number;
  /** More than `count` exist (the list is capped at one page). */
  more: boolean;
}
/** Pending approvals: counted over one page of 100 so the number on Config home equals what the change list shows. */
export async function pendingCount(token: string): Promise<ApiOutcome<PendingCount>> {
  const r = await changesOf(token, { status: "pending_approval", limit: 100 });
  return r.ok ? { ...r, data: { count: r.data.items.length, more: r.data.next_cursor !== null } } : r;
}

/** Share in percent with one decimal, or null when nothing was targeted. */
export function sharePct(n: number, of: number): number | null {
  return of <= 0 ? null : Math.round((n / of) * 1000) / 10;
}

export type { ConfigChange };

/** Values at the GLOBAL (or default) level of a version: the rows the console edits. A zone or route override never stands in for them. */
export function globalValues(detail: ConfigVersionDetail): Record<string, ResolvedConfigValue> {
  const out: Record<string, ResolvedConfigValue> = {};
  for (const v of detail.values) if (v.scope_type === "global" || v.scope_type === "default") out[v.key] = v;
  return out;
}
