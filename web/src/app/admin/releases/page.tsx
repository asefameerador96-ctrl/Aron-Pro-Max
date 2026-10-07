import { ReleasesView } from "@/components/admin/config/releases-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import { adoptionByVersion } from "@/lib/admin/adoption";
import { latestVersion, listKeys, versionDetail } from "@/lib/admin/config-load";
import type { AppReleasePage, Device, DevicePage, ReleasePolicyList, ResolvedConfigValue } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

const POLICY_KEYS = ["cfg.release.min_version_code", "cfg.release.blocked_version_codes"];

export default async function ReleasesPage() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const [rel, policy, keys, ver] = await Promise.all([
    apiGet<AppReleasePage>("/v1/admin/releases", session.at, { limit: 100 }),
    apiGet<ReleasePolicyList>("/v1/admin/releases/policy", session.at),
    listKeys(session.at, "release"),
    latestVersion(session.at),
  ]);
  if (!rel.ok) return onApiFailure(rel.status, rel.problem, locale);
  const values: Record<string, ResolvedConfigValue> = {};
  if (ver.ok && ver.data) {
    const d = await versionDetail(session.at, ver.data.version);
    if (d.ok) for (const v of d.data.values) values[v.key] = v;
  }
  const apkDefault = keys.ok ? keys.data.items.find((k) => k.key === "cfg.release.apk_max_mb")?.default_value : undefined;
  const apkMax = Number(values["cfg.release.apk_max_mb"]?.value ?? apkDefault ?? 30);
  const devices: Device[] = [];
  let cursor: string | undefined;
  for (let i = 0; i < 10; i++) {
    const r = await apiGet<DevicePage>("/v1/admin/devices", session.at, { cursor, limit: 500 });
    if (!r.ok) break;
    devices.push(...r.data.items);
    if (!r.data.next_cursor) break;
    cursor = r.data.next_cursor;
  }
  return (
    <ReleasesView
      locale={locale}
      releases={rel.data.items}
      policy={policy.ok ? policy.data.items : []}
      adoption={adoptionByVersion(devices)}
      policyKeys={keys.ok ? keys.data.items.filter((k) => POLICY_KEYS.includes(k.key)) : []}
      policyValues={values}
      apkMaxMb={Number.isFinite(apkMax) && apkMax > 0 ? apkMax : 30}
      canWrite={canOp("release.update", session.user.role)}
      canPublish={canOp("release.publish", session.user.role)}
    />
  );
}
