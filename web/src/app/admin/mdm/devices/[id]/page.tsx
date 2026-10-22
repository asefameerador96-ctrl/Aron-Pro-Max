import { notFound } from "next/navigation";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet } from "@/components/admin/kit/page";
import { MdmDeviceDetailView } from "@/components/admin/mdm/device-detail-view";
import { mdmContext } from "@/components/admin/mdm/shared";
import type { MdmActivityPage, MdmDeviceDetail, MdmPolicyPage } from "@/lib/admin/mdm";

export default async function MdmDevicePage({ params }: { params: Promise<{ id: string }> }) {
  const [{ ctx, forbidden }, { id }] = await Promise.all([mdmContext(), params]);
  if (forbidden) return forbidden;
  if (!/^[1-9]\d{0,14}$/.test(id)) notFound();
  const [d, act, pols] = await Promise.all([
    apiGet<MdmDeviceDetail>(`/v1/admin/mdm/devices/${id}`, ctx.session.at),
    apiGet<MdmActivityPage>(`/v1/admin/mdm/devices/${id}/activity`, ctx.session.at, { limit: 30 }),
    ctx.level === "w" ? apiGet<MdmPolicyPage>("/v1/admin/mdm/policies", ctx.session.at, { limit: 100 }) : Promise.resolve(null),
  ]);
  if (!d.ok) return onApiFailure(d.status, d.problem, ctx.locale);
  const policies = pols && pols.ok ? pols.data.items.map((p) => ({ id: p.policy_id, name: p.name })) : [];
  return <MdmDeviceDetailView locale={ctx.locale} detail={d.data} activity={act.ok ? act.data.items : []} policies={policies} level={ctx.level} me={ctx.session.user.username} />;
}
