import { DeviceDetailView } from "@/components/admin/config/device-detail-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import type { Device, DeviceStatusPage } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { notFound } from "next/navigation";

export default async function DevicePage({ params }: { params: Promise<{ id: string }> }) {
  const [session, locale, { id }] = await Promise.all([requireSession(), getLocale(), params]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  if (!/^[1-9]\d{0,14}$/.test(id)) notFound();
  const [d, h, dirs] = await Promise.all([
    apiGet<Device>(`/v1/admin/devices/${id}`, session.at),
    apiGet<DeviceStatusPage>(`/v1/admin/devices/${id}/status-history`, session.at, { limit: 20 }),
    apiGet<{ items: import("@/lib/admin/types").Directive[] }>(`/v1/admin/devices/${id}/directives`, session.at),
  ]);
  if (!d.ok) return onApiFailure(d.status, d.problem, locale);
  return <DeviceDetailView locale={locale} device={d.data} history={h.ok ? h.data.items : []} directives={dirs.ok ? dirs.data.items : []} canWrite={canOp("device.state", session.user.role)} />;
}
