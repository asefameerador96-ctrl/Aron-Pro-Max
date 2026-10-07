import { FinalSubmitPanel, FinalSubmitPicker, zoneFinalOf } from "@/components/dash/final-submit-panel";
import { Tile } from "@/components/dash/tiles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { getLoginSubmit, getSummary } from "@/lib/dash/server";
import { businessDate, problemMessage, t } from "@/lib/i18n";

// Final Submit status with the zone picker (F-WEB-047): every zone carries a Done or Pending badge, and choosing one shows its own panel.
export default async function FinalSubmitPage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const [sp, locale, session] = await Promise.all([searchParams, getLocale(), requireSession()]);
  const today = businessDate();
  const [top, ls] = await Promise.all([getSummary(session.at, { from: today, to: today }), getLoginSubmit(session.at, today)]);
  const zoneFinal = ls.ok ? zoneFinalOf(ls.data.zones) : undefined; // missing or failed: the KPI estimate stays
  if (!top.ok)
    return (
      <p role="alert" className="rounded bg-red-50 p-3 text-red-900">
        {problemMessage(locale, top.problem.code)}
      </p>
    );
  const node = sp.node && /^\d{1,12}$/.test(sp.node) ? top.data.children.find((c) => String(c.node.id) === sp.node) : undefined;
  const one = node ? await getSummary(session.at, { from: today, to: today, level: node.node.type, node_id: node.node.id }) : null;
  const shown = one?.ok ? one.data : top.data;
  return (
    <div className="space-y-4" data-testid="final-submit-page">
      <h1 className="text-2xl font-bold">{t(locale, "menu.final_submit")}</h1>
      <FinalSubmitPicker locale={locale} zones={top.data.children} selected={sp.node} zoneFinal={zoneFinal} />
      <Tile id="finalsubmit" locale={locale} titleKey="dashboard.final_submit" date={{ businessDate: today, asOf: shown.as_of, today }}>
        <FinalSubmitPanel locale={locale} kpis={shown.kpis} zones={shown.children} zoneFinal={zoneFinal} />
      </Tile>
    </div>
  );
}
