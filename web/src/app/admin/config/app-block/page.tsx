import { AppBlockView } from "@/components/admin/config/app-block-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import { globalValues, latestVersion, listKeys, versionDetail } from "@/lib/admin/config-load";
import type { ResolvedConfigValue } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

export default async function AppBlockPage() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const keys = await listKeys(session.at, "device");
  if (!keys.ok) return onApiFailure(keys.status, keys.problem, locale);
  const ver = await latestVersion(session.at);
  const v = ver.ok ? (ver.data?.version ?? null) : null;
  const values: Record<string, ResolvedConfigValue> = {};
  if (v !== null) {
    const d = await versionDetail(session.at, v);
    if (d.ok) Object.assign(values, globalValues(d.data));
  }
  return <AppBlockView locale={locale} keys={keys.data.items} values={values} version={v} canWrite={canOp("config.change", session.user.role)} />;
}
