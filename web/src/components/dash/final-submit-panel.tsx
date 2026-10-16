import type { Schemas } from "@/contract/types";
import type { Kpis, Summary } from "@/lib/dash/server";
import { t, type Locale } from "@/lib/i18n";
import { Icon } from "@/components/kit/icons";
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

/** Final-submit status (F-WEB-047; screens-web s1 "status rows, not a chart"): zones submitted versus remaining today, Submit %
 *  and Day-completion %, and a Done / Pending chip per zone (check icon or hollow circle plus the zone name, never colour alone). */
export function FinalSubmitPanel({ locale, kpis, zones, zoneFinal }: { locale: Locale; kpis: Kpis; zones: Summary["children"]; zoneFinal?: ZoneFinal }) {
  const total = kpis.zones_with_target_routes ?? 0;
  const done = kpis.zones_final_submitted ?? 0;
  return (
    <div data-testid="final-submit-panel" className="flex flex-col gap-2">
      <p className="text-body-strong text-primary tabular-nums" data-testid="final-submit-counts">
        {t(locale, "finalsubmit.zones_counts", { done: n(locale, done), remaining: n(locale, Math.max(0, total - done)) })}
      </p>
      <dl className="grid grid-cols-2 gap-2">
        <div>
          <dt className="text-caption text-secondary">{t(locale, "finalsubmit.submit_pct")}</dt>
          <dd className="whitespace-nowrap text-numeral text-primary tabular-nums" data-testid="submit-pct">
            {pctText(locale, kpis.submit_pct_of_logged_in)}
          </dd>
        </div>
        <div>
          <dt className="text-caption text-secondary">{t(locale, "finalsubmit.completion_pct")}</dt>
          <dd className="whitespace-nowrap text-numeral text-primary tabular-nums" data-testid="completion-pct">
            {pctText(locale, kpis.day_completion_pct)}
          </dd>
        </div>
      </dl>
      <ul className="flex flex-wrap gap-1.5" data-testid="zone-badges">
        {zones.map((c) => {
          const finished = finishedNode(c, zoneFinal);
          return (
            <li key={`${c.node.type}-${c.node.id}`} data-zone={c.node.id} data-state={finished ? "done" : "pending"} className={`inline-flex min-h-7 items-center gap-1 rounded-chip px-2.5 text-label ${finished ? "bg-success-container text-success-on-container" : "bg-warning-container text-warning-on-container"}`}>
              <Icon name={finished ? "check-circle" : "circle"} size={16} />
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
      <label className="text-label text-secondary">
        <span className="block">{t(locale, "scope.zone")}</span>
        <select name="node" defaultValue={selected ?? ""} className="mt-1 min-h-(--ctl-h) rounded-chip border border-input bg-raised px-3 text-body text-primary focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
          <option value="">{t(locale, "common.all")}</option>
          {zones.map((c) => (
            <option key={`${c.node.type}-${c.node.id}`} value={c.node.id} data-state={finishedNode(c, zoneFinal) ? "done" : "pending"}>
              {finishedNode(c, zoneFinal) ? "✓ " : "○ "}
              {c.node.name ?? c.node.id} — {t(locale, finishedNode(c, zoneFinal) ? "finalsubmit.done" : "finalsubmit.pending")}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" className="inline-flex min-h-(--ctl-h) items-center gap-2 rounded-full border border-divider bg-raised px-5 text-body-strong text-accent hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
        {t(locale, "common.filter")}
      </button>
    </form>
  );
}
