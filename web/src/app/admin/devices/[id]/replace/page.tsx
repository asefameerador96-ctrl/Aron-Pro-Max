import { DeviceReplaceView } from "@/components/admin/config/device-replace-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import type { Device } from "@/lib/admin/types";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { notFound } from "next/navigation";

export default async function ReplaceDevicePage({ params }: { params: Promise<{ id: string }> }) {
  const [session, locale, { id }] = await Promise.all([requireSession(), getLocale(), params]);
  if (!canOp("device.replace", session.user.role)) return <Forbidden locale={locale} />;
  if (!/^[1-9]\d{0,14}$/.test(id)) notFound();
  const d = await apiGet<Device>(`/v1/admin/devices/${id}`, session.at);
  if (!d.ok) return onApiFailure(d.status, d.problem, locale);
  return <DeviceReplaceView locale={locale} device={d.data} />;
}
