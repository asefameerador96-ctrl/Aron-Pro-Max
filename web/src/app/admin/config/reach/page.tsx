import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, qs, type SearchParams } from "@/components/admin/kit/page";
import { ReachView } from "@/components/admin/config/reach-view";
import { Forbidden } from "@/components/forbidden";
import { latestVersion, reachOf } from "@/lib/admin/config-load";
import type { ConfigPendingDevicePage } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

const id = (v: string | undefined) => (v && /^[1-9]\d{0,14}$/.test(v) ? v : undefined);

export default async function ReachPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  let version = id(one(sp.version, 15));
  if (!version) {
    const l = await latestVersion(session.at);
    if (l.ok && l.data) version = String(l.data.version);
  }
  const zone = id(one(sp.zone, 15));
  if (!version) return <ReachView locale={locale} reach={null} pending={[]} version={null} zone={zone ?? ""} nextHref={null} />;
  const cursor = one(sp.cursor, 512);
  const [reach, pending] = await Promise.all([reachOf(session.at, Number(version), zone), apiGet<ConfigPendingDevicePage>(`/v1/admin/config/reach/${version}/pending`, session.at, { zone_id: zone, cursor, limit: 100 })]);
  if (!pending.ok) return onApiFailure(pending.status, pending.problem, locale);
  const nextHref = pending.data.next_cursor ? `/admin/config/reach${qs({ version, zone, cursor: pending.data.next_cursor })}` : null;
  return <ReachView locale={locale} reach={reach.ok ? reach.data : null} pending={pending.data.items} version={Number(version)} zone={zone ?? ""} nextHref={nextHref} />;
}
