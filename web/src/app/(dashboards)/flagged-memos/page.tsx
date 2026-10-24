import { FlaggedMemosView } from "@/components/dash/flagged-memos-view";
import { Forbidden } from "@/components/forbidden";
import { ScopeFilter } from "@/components/reports/scope-filter";
import { hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { FLAGGED_MEMO_ROLES, flaggedHref, getFlaggedMemos, parseFlaggedQuery } from "@/lib/dash/flagged-memos";
import { businessDate } from "@/lib/i18n";
import { GEO_LEVELS, boundGeo, geoFromParams } from "@/lib/reports/query";

// The dashboard endpoint takes no route node: the picker stops at the zone.
const LEVELS = GEO_LEVELS.filter((l) => l !== "route");

// Flagged memos (D-WEB-FLAGGED-MEMOS-01): the server's review list, newest stored first, read from GET /v1/dashboards/flagged-memos.
// Scope comes from the token: the page sends only a node of the picker, which the server checks again (403 outside the reach).
// While the endpoint is not deployed (404) or fails, the page shows its plain error state with a retry.
export default async function FlaggedMemosPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const [sp, locale, session] = await Promise.all([searchParams, getLocale(), requireSession()]);
  if (!hasRole(session.user.role, FLAGGED_MEMO_ROLES)) return <Forbidden locale={locale} />;
  const today = businessDate();
  const q = parseFlaggedQuery(sp, today);
  const picked = boundGeo(session.scope, geoFromParams(sp)) ?? {};
  const deepest = [...LEVELS].reverse().find((l) => picked[l]?.[0] !== undefined);
  const node = deepest ? { level: deepest, node_id: picked[deepest]![0]! } : {};
  // The picker's own parameters, kept in the paging and reset links.
  const geo: Record<string, string | undefined> = {};
  for (const l of LEVELS) if (picked[l]?.[0] !== undefined) geo[l] = String(picked[l]![0]);
  const result = q.rangeError ? null : await getFlaggedMemos(session.at, { from: q.from, to: q.to, flag: q.flag, cursor: q.cursor, ...node });
  const cursor = result?.ok ? result.data.next_cursor : null;
  return (
    <FlaggedMemosView
      locale={locale}
      today={today}
      query={q}
      scopeFilter={<ScopeFilter token={session.at} scope={session.scope} locale={locale} params={sp} levels={LEVELS} />}
      result={result}
      firstHref={q.cursor ? flaggedHref(q, geo) : null}
      nextHref={cursor ? flaggedHref(q, geo, cursor) : null}
      resetHref="/flagged-memos"
    />
  );
}
