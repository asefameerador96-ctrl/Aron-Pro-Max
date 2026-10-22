import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, qs, type SearchParams } from "@/components/admin/kit/page";
import { MdmActivityView } from "@/components/admin/mdm/lists-views";
import { mdmContext } from "@/components/admin/mdm/shared";
import type { MdmActivityPage } from "@/lib/admin/mdm";

export default async function MdmActivityPageRoute({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [{ ctx, forbidden }, sp] = await Promise.all([mdmContext(), searchParams]);
  if (forbidden) return forbidden;
  const r = await apiGet<MdmActivityPage>("/v1/admin/mdm/activity", ctx.session.at, { cursor: one(sp.cursor, 512), limit: 50 });
  if (!r.ok) return onApiFailure(r.status, r.problem, ctx.locale);
  const nextHref = r.data.next_cursor ? `/admin/mdm/activity${qs({ cursor: r.data.next_cursor })}` : null;
  return <MdmActivityView locale={ctx.locale} rows={r.data.items} nextHref={nextHref} />;
}
