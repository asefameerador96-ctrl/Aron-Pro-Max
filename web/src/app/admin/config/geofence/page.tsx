import { GeofenceView } from "@/components/admin/config/geofence-view";
import { apiGet, one, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import { listKeys } from "@/lib/admin/config-load";
import type { BlastRadius, CalibrationReport, ConfigScopeType, DensityReport, RadiusWhatIf, ResolvedConfigValue } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { notFound } from "next/navigation";

const RADIUS_KEY = "cfg.geo.radius_m";

export default async function GeofencePage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const keys = await listKeys(session.at, "geo");
  const key = keys.ok ? keys.data.items.find((k) => k.key === RADIUS_KEY) : undefined;
  if (!key) notFound();
  const lv = one(sp.level, 16);
  const level = (lv && key.scope_levels.includes(lv as ConfigScopeType) ? lv : "") as ConfigScopeType | "";
  const idText = one(sp.id, 15) ?? "";
  const id = level === "global" ? "0" : /^[1-9]\d{0,14}$/.test(idText) ? idText : "";
  const chosen = level !== "" && id !== "";
  const val = one(sp.value, 5);
  const value = val && /^\d{2,4}$/.test(val) && Number(val) >= 10 && Number(val) <= 5000 ? Number(val) : null;
  const d = one(sp.days, 2);
  const days = d && /^\d{1,2}$/.test(d) && Number(d) >= 1 && Number(d) <= 90 ? Number(d) : 30;
  const scope = { scope_type: level, scope_id: id };
  const [current, blast, density, calibration, whatIf] = chosen
    ? await Promise.all([
        apiGet<ResolvedConfigValue>("/v1/admin/config/resolve", session.at, { key: RADIUS_KEY, node_type: level, node_id: id }),
        apiGet<BlastRadius>("/v1/admin/config/blast-radius", session.at, scope),
        apiGet<DensityReport>("/v1/admin/config/density", session.at, scope),
        apiGet<CalibrationReport>("/v1/admin/config/calibration", session.at, scope),
        value === null ? null : apiGet<RadiusWhatIf>("/v1/admin/config/whatif", session.at, { ...scope, value, days }),
      ])
    : [null, null, null, null, null];
  return <GeofenceView locale={locale} key_={key} level={level} areaId={id} current={current?.ok ? current.data : null} whatIfValue={value} whatIfDays={days} whatIf={whatIf?.ok ? whatIf.data : null} blast={blast?.ok ? blast.data : null} density={density?.ok ? density.data : null} calibration={calibration?.ok ? calibration.data : null} canWrite={canOp("config.change", session.user.role)} />;
}
