import { notFound } from "next/navigation";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet } from "@/components/admin/kit/page";
import { PolicyEditor } from "@/components/admin/mdm/policy-editor";
import { PolicyPageFrame } from "@/components/admin/mdm/policies-view";
import { mdmContext } from "@/components/admin/mdm/shared";
import type { MdmPolicy } from "@/lib/admin/mdm";

export default async function MdmPolicyPage({ params }: { params: Promise<{ id: string }> }) {
  const [{ ctx, forbidden }, { id }] = await Promise.all([mdmContext(), params]);
  if (forbidden) return forbidden;
  if (!/^[1-9]\d{0,14}$/.test(id)) notFound();
  const r = await apiGet<MdmPolicy>(`/v1/admin/mdm/policies/${id}`, ctx.session.at);
  if (!r.ok) return onApiFailure(r.status, r.problem, ctx.locale);
  return (
    <PolicyPageFrame locale={ctx.locale} title={r.data.name}>
      <PolicyEditor initial={r.data} canWrite={ctx.level === "w"} />
    </PolicyPageFrame>
  );
}
