// The reach widget: how many of the targeted phones applied and acknowledged a config version (P1, P7, console).
import { Stat } from "@/components/admin/kit/page";
import { sharePct } from "@/lib/admin/config-load";
import type { ConfigReach } from "@/lib/admin/types";
import { formatNumber, t, type Locale } from "@/lib/i18n";

export function ReachWidget({ locale, reach }: { locale: Locale; reach: ConfigReach | null }) {
  if (!reach) {
    return (
      <p className="text-sm text-slate-600" data-testid="reach-none">
        {t(locale, "cfgc.reach.none")}
      </p>
    );
  }
  const applied = sharePct(reach.devices_applied, reach.devices_targeted);
  const acked = sharePct(reach.devices_acked, reach.devices_targeted);
  const pct = (v: number | null) => (v === null ? "—" : `${formatNumber(locale, v, { maximumFractionDigits: 1 })}%`);
  return (
    <div className="space-y-2" data-testid="reach-widget">
      <p className="text-sm text-slate-600">{t(locale, "cfgc.reach.of_version", { version: formatNumber(locale, reach.version, { useGrouping: false }) })}</p>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Stat label={t(locale, "cfgc.reach.targeted")} value={formatNumber(locale, reach.devices_targeted)} testId="reach-targeted" />
        <Stat label={t(locale, "cfgc.reach.applied")} value={`${formatNumber(locale, reach.devices_applied)} (${pct(applied)})`} testId="reach-applied" />
        <Stat label={t(locale, "cfgc.reach.acked")} value={`${formatNumber(locale, reach.devices_acked)} (${pct(acked)})`} testId="reach-acked" />
        <Stat label={t(locale, "cfgc.reach.pending")} value={formatNumber(locale, reach.devices_pending)} testId="reach-pending" />
      </div>
      {reach.p95_reach_min != null ? <p className="text-xs text-slate-500">{t(locale, "cfgc.reach.p95", { min: formatNumber(locale, reach.p95_reach_min, { maximumFractionDigits: 0 }) })}</p> : null}
    </div>
  );
}
