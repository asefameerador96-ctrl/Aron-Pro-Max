import type { Schemas } from "@/contract/types";
import type { Kpis, Summary } from "@/lib/dash/server";
import { t, type Locale } from "@/lib/i18n";
import { n, pctText } from "./tiles";

/** A zone is Done only when every target zone-route is FINAL-submitted (not merely sales-submitted: day_completion_pct is sales-based). */
export function isFinal(k: Kpis): boolean {
  if ((k.zones_with_target_routes ?? 0) > 0) return k.zones_final_submitted === k.zones_with_target_routes;
  return k.final_submit_pct === 100;
}

/** Per-zone final-submit state from `LoginSubmitStatus.zones` (contract v1.2), keyed by zone id. When it knows a zone it wins over the KPI estimate. */
export type ZoneFinal = ReadonlyMap<number, boolean>;
export const zoneFinalOf = (zones: Schemas["LoginSubmitStatus"]["zones"]): ZoneFinal | undefined => (zones ? new Map(zones.map((z) => [z.zone_id, z.final_submitted])) : undefined);
const finishedNode = (c: Summary["children"][number], zf?: ZoneFinal): boolean => (c.node.type === "zone" ? zf?.get(c.node.id) : undefined) ?? isFinal(c.kpis);

/** Final-submit status (F-WEB-047): zones submitted versus remaining today, Submit % and Day-completion %, and a Done / Pending
 *  badge per zone. The badge for a child comes from its day-completion figure (100 = every route of the zone final-submitted). */
export function FinalSubmitPanel({ locale, kpis, zones, zoneFinal }: { locale: Locale; kpis: Kpis; zones: Summary["children"]; zoneFinal?: ZoneFinal }) {
  const total = kpis.zones_with_target_routes ?? 0;
  const done = kpis.zones_final_submitted ?? 0;
  return (
    <div data-testid="final-submit-panel">
      <p className="text-lg font-semibold tabular-nums" data-testid="final-submit-counts">
        {t(locale, "finalsubmit.zones_counts", { done: n(locale, done), remaining: n(locale, Math.max(0, total - done)) })}
      </p>
      <dl className="mt-2 grid grid-cols-2 gap-2 text-sm">
        <div>
          <dt className="text-slate-500">{t(locale, "finalsubmit.submit_pct")}</dt>
          <dd className="font-semibold tabular-nums" data-testid="submit-pct">
            {pctText(locale, kpis.submit_pct_of_logged_in)}
          </dd>
        </div>
        <div>
          <dt className="text-slate-500">{t(locale, "finalsubmit.completion_pct")}</dt>
          <dd className="font-semibold tabular-nums" data-testid="completion-pct">
            {pctText(locale, kpis.day_completion_pct)}
          </dd>
        </div>
      </dl>
      <ul className="mt-2 flex flex-wrap gap-2 text-xs" data-testid="zone-badges">
        {zones.map((c) => {
          const finished = finishedNode(c, zoneFinal);
          return (
            <li key={`${c.node.type}-${c.node.id}`} data-zone={c.node.id} data-state={finished ? "done" : "pending"} className={`rounded-full px-2 py-0.5 ${finished ? "bg-emerald-100 text-emerald-900" : "bg-amber-100 text-amber-900"}`}>
              {c.node.name ?? c.node.code ?? c.node.id} · {t(locale, finished ? "finalsubmit.done" : "finalsubmit.pending")}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** The picker: a select whose options carry the per-zone badge text, so it works without script. */
export function FinalSubmitPicker({ locale, zones, selected, zoneFinal }: { locale: Locale; zones: Summary["children"]; selected?: string; zoneFinal?: ZoneFinal }) {
  return (
    <form method="get" className="flex flex-wrap items-end gap-2" data-testid="final-submit-picker">
      <label className="text-sm">
        <span className="block text-xs text-slate-500">{t(locale, "scope.zone")}</span>
        <select name="node" defaultValue={selected ?? ""} className="rounded border border-slate-300 bg-white px-2 py-1">
          <option value="">{t(locale, "common.all")}</option>
          {zones.map((c) => (
            <option key={`${c.node.type}-${c.node.id}`} value={c.node.id} data-state={finishedNode(c, zoneFinal) ? "done" : "pending"}>
              {finishedNode(c, zoneFinal) ? "✓ " : "○ "}
              {c.node.name ?? c.node.id} — {t(locale, finishedNode(c, zoneFinal) ? "finalsubmit.done" : "finalsubmit.pending")}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" className="rounded bg-brand-700 px-3 py-1.5 text-sm font-semibold text-white">
        {t(locale, "common.filter")}
      </button>
    </form>
  );
}
