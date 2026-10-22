import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, qs, type SearchParams } from "@/components/admin/kit/page";
import { PoliciesListView } from "@/components/admin/mdm/policies-view";
import { mdmContext } from "@/components/admin/mdm/shared";
import type { MdmPolicyPage } from "@/lib/admin/mdm";

export default async function MdmPoliciesPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [{ ctx, forbidden }, sp] = await Promise.all([mdmContext(), searchParams]);
  if (forbidden) return forbidden;
  const r = await apiGet<MdmPolicyPage>("/v1/admin/mdm/policies", ctx.session.at, { cursor: one(sp.cursor, 512), limit: 50 });
  if (!r.ok) return onApiFailure(r.status, r.problem, ctx.locale);
  const nextHref = r.data.next_cursor ? `/admin/mdm/policies${qs({ cursor: r.data.next_cursor })}` : null;
  return <PoliciesListView locale={ctx.locale} rows={r.data.items} level={ctx.level} nextHref={nextHref} />;
}
