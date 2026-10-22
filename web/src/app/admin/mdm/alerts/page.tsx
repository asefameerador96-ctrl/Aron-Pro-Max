import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, qs, type SearchParams } from "@/components/admin/kit/page";
import { MdmAlertsView } from "@/components/admin/mdm/lists-views";
import { mdmContext } from "@/components/admin/mdm/shared";
import { MDM_FLAGS, type MdmAlertPage } from "@/lib/admin/mdm";

export default async function MdmAlertsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [{ ctx, forbidden }, sp] = await Promise.all([mdmContext(), searchParams]);
  if (forbidden) return forbidden;
  const f = one(sp.flag, 24);
  const flag = f && (MDM_FLAGS as readonly string[]).includes(f) ? f : "";
  const r = await apiGet<MdmAlertPage>("/v1/admin/mdm/alerts", ctx.session.at, { flag, cursor: one(sp.cursor, 512), limit: 50 });
  if (!r.ok) return onApiFailure(r.status, r.problem, ctx.locale);
  const nextHref = r.data.next_cursor ? `/admin/mdm/alerts${qs({ flag, cursor: r.data.next_cursor })}` : null;
  return <MdmAlertsView locale={ctx.locale} rows={r.data.items} flag={flag} nextHref={nextHref} />;
}
