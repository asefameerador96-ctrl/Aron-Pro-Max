import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet } from "@/components/admin/kit/page";
import { MdmEnrolmentView } from "@/components/admin/mdm/lists-views";
import { mdmContext } from "@/components/admin/mdm/shared";
import type { EnrolmentTokenPage } from "@/lib/admin/types";

export default async function MdmEnrolmentPage() {
  const { ctx, forbidden } = await mdmContext();
  if (forbidden) return forbidden;
  const r = await apiGet<EnrolmentTokenPage>("/v1/admin/mdm/enrolment-tokens", ctx.session.at, { active_only: "true", limit: 50 });
  if (!r.ok) return onApiFailure(r.status, r.problem, ctx.locale);
  return <MdmEnrolmentView locale={ctx.locale} tokens={r.data.items} level={ctx.level} />;
}
