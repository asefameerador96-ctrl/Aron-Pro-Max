import { ConfigHomeView } from "@/components/admin/config/config-home-view";
import { Forbidden } from "@/components/forbidden";
import { changesOf, latestVersion, pendingCount, reachOf } from "@/lib/admin/config-load";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

export default async function ConfigHomePage() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const [version, pending, recent] = await Promise.all([latestVersion(session.at), pendingCount(session.at), changesOf(session.at, { limit: 10 })]);
  const v = version.ok ? version.data?.version ?? null : null;
  const reach = v === null ? null : await reachOf(session.at, v);
  return <ConfigHomeView locale={locale} version={v} pending={pending.ok ? pending.data : null} recent={recent.ok ? recent.data.items : []} reach={reach?.ok ? reach.data : null} />;
}
