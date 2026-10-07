import { DevicesView } from "@/components/admin/config/devices-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, qs, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import type { DevicePage } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

const pick = (v: string | undefined, allowed: readonly string[]) => (v && allowed.includes(v) ? v : "");

export default async function DevicesPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const q = one(sp.q, 80) ?? "";
  const zone = one(sp.zone_id, 15);
  const filters = {
    status: pick(one(sp.status, 16), ["enrolled", "active", "suspended", "revoked", "replaced"]),
    trust_level: pick(one(sp.trust_level, 16), ["high", "normal", "low", "blocked"]),
    flavour: pick(one(sp.flavour, 8), ["sr", "amo", "tso"]),
    q: q.length >= 2 ? q : "",
    zone_id: zone && /^[1-9]\d{0,14}$/.test(zone) ? zone : "",
  };
  const r = await apiGet<DevicePage>("/v1/admin/devices", session.at, { ...filters, cursor: one(sp.cursor, 512), limit: 50 });
  if (!r.ok) return onApiFailure(r.status, r.problem, locale);
  const nextHref = r.data.next_cursor ? `/admin/devices${qs({ ...filters, cursor: r.data.next_cursor })}` : null;
  return <DevicesView locale={locale} rows={r.data.items} filters={filters} nextHref={nextHref} canWrite={canOp("device.state", session.user.role)} />;
}
