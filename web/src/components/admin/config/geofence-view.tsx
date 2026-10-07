// F-ADM-039 Config page P2: geofence radius at each scope level, with a map, density view and what-if on stored fixes.
import Link from "next/link";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { Card, PageHeading, qs } from "@/components/admin/kit/page";
import type { BlastRadius, CalibrationReport, ConfigKey, ConfigScopeType, DensityReport, RadiusWhatIf, ResolvedConfigValue } from "@/lib/admin/types";
import { formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { ConfigSetForm } from "./config-set-form";
import { RadiusMap } from "./radius-map";

export interface GeofenceViewProps {
  locale: Locale;
  key_: ConfigKey;
  level: ConfigScopeType | "";
  areaId: string;
  current: ResolvedConfigValue | null;
  whatIfValue: number | null;
  whatIfDays: number;
  whatIf: RadiusWhatIf | null;
  blast: BlastRadius | null;
  density: DensityReport | null;
  calibration: CalibrationReport | null;
  canWrite: boolean;
}

type CalRow = CalibrationReport["rows"][number];
type DenRow = DensityReport["rows"][number];

export function GeofenceView({ locale, key_, level, areaId, current, whatIfValue, whatIfDays, whatIf, blast, density, calibration, canWrite }: GeofenceViewProps) {
  const chosen = level !== "" && (level === "global" || /^[1-9]\d*$/.test(areaId));
  const here = { level, id: level === "global" ? "0" : areaId };
  const radius = Number(current?.value ?? key_.default_value);
  const n = (v: number) => formatNumber(locale, v);
  const calCols: Column<CalRow>[] = [
    { key: "t", header: t(locale, "geo.cal.col.territory"), render: (r) => n(r.territory_id) },
    { key: "c", header: t(locale, "geo.cal.col.class"), render: (r) => r.geo_class ?? "—" },
    { key: "v", header: t(locale, "geo.cal.col.visits"), render: (r) => n(r.visits), align: "right" },
    { key: "f", header: t(locale, "geo.cal.col.force"), render: (r) => (r.force_sale_pct == null ? "—" : `${formatNumber(locale, r.force_sale_pct, { maximumFractionDigits: 1 })}%`), align: "right" },
    {
      key: "h",
      header: t(locale, "geo.cal.col.hist"),
      render: (r) => {
        const max = Math.max(1, ...r.histogram.map((b) => b.visits));
        return (
          <span className="flex items-end gap-px" role="img" aria-label={r.histogram.map((b) => `≤${b.upper_m} m: ${b.visits}`).join("; ")}>
            {r.histogram.map((b) => (
              <span key={b.upper_m} className="w-1.5 bg-brand-600" style={{ height: `${Math.max(2, Math.round((b.visits / max) * 32))}px` }} />
            ))}
          </span>
        );
      },
    },
    {
      key: "s",
      header: t(locale, "geo.cal.col.suggested"),
      render: (r) =>
        r.suggested_radius_m == null ? (
          "—"
        ) : (
          <Link className="text-brand-700 underline" href={`/admin/config/geofence${qs({ level: here.level, id: areaId, value: r.suggested_radius_m, days: whatIfDays })}`}>
            {t(locale, "geo.metres", { m: n(r.suggested_radius_m) })} · {t(locale, "geo.cal.use")}
          </Link>
        ),
    },
  ];
  const denCols: Column<DenRow>[] = [
    { key: "n", header: t(locale, "geo.density.col.node"), render: (r) => `${r.node.name ?? r.node.code ?? ""} #${r.node.id}` },
    { key: "o", header: t(locale, "geo.density.col.outlets"), render: (r) => n(r.outlets), align: "right" },
    { key: "i", header: t(locale, "geo.density.col.index"), render: (r) => (r.density_index_pct == null ? "—" : `${formatNumber(locale, r.density_index_pct, { maximumFractionDigits: 1 })}%`), align: "right" },
    ...(density?.radii_m ?? []).map((m, i): Column<DenRow> => ({ key: `r${m}`, header: t(locale, "geo.density.col.neighbours", { r: n(m) }), render: (r) => (r.median_neighbours[i] == null ? "—" : formatNumber(locale, r.median_neighbours[i]!, { maximumFractionDigits: 1 })), align: "right" })),
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "geo.title")} intro={t(locale, "geo.intro")} />
      <form method="get" className="flex flex-wrap items-end gap-3 rounded-lg border border-slate-200 bg-white p-3" data-testid="scope-picker">
        <label className="text-sm">
          <span className="mb-1 block">{t(locale, "geo.level")}</span>
          <select name="level" defaultValue={level} className="rounded border border-slate-300 px-2 py-1.5 text-sm">
            <option value="">{t(locale, "geo.pick")}</option>
            {key_.scope_levels.map((l) => (
              <option key={l} value={l}>
                {t(locale, `cfgc.scope.${l}` as MessageKey)}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm">
          <span className="mb-1 block">{t(locale, "geo.area_id")}</span>
          <input name="id" defaultValue={areaId} inputMode="numeric" className="w-32 rounded border border-slate-300 px-2 py-1.5 text-sm" />
        </label>
        <button type="submit" className="rounded bg-brand-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-brand-700">{t(locale, "geo.show")}</button>
      </form>
      {!chosen ? (
        <p className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">{t(locale, "geo.need_id")}</p>
      ) : (
        <>
          <Card testId="geo-current">
            <p>
              {t(locale, "geo.current")}: <strong data-testid="current-radius">{t(locale, "geo.metres", { m: n(radius) })}</strong>{" "}
              <span className="text-sm text-slate-500">({current ? t(locale, `cfgk.from.${current.scope_type}` as MessageKey) : t(locale, "cfgk.from.default")})</span>
            </p>
            <RadiusMap radiusM={whatIfValue ?? radius} />
          </Card>
          {canWrite ? <ConfigSetForm keyName={key_.key} valueType={key_.value_type} bounds={key_.bounds} scopeLevels={key_.scope_levels} scope={{ type: level as ConfigScopeType, id: Number(here.id) }} current={radius} label={t(locale, "geo.whatif.value")} testId="radius-form" /> : <p className="text-sm text-slate-600">{t(locale, "cfgc.read_only")}</p>}
          <Card title={t(locale, "geo.whatif")} testId="whatif">
            <form method="get" className="flex flex-wrap items-end gap-3">
              <input type="hidden" name="level" value={level} />
              <input type="hidden" name="id" value={areaId} />
              <label className="text-sm">
                <span className="mb-1 block">{t(locale, "geo.whatif.value")}</span>
                <input name="value" defaultValue={whatIfValue ?? ""} inputMode="numeric" className="w-28 rounded border border-slate-300 px-2 py-1.5 text-sm" />
              </label>
              <label className="text-sm">
                <span className="mb-1 block">{t(locale, "geo.whatif.days")}</span>
                <input name="days" defaultValue={whatIfDays} inputMode="numeric" className="w-20 rounded border border-slate-300 px-2 py-1.5 text-sm" />
              </label>
              <button type="submit" className="rounded border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100">{t(locale, "geo.whatif.run")}</button>
            </form>
            {whatIf ? <p className="text-sm" data-testid="whatif-result">{t(locale, "geo.whatif.result", { evaluated: n(whatIf.visits_evaluated), valid: n(whatIf.to_valid), invalid: n(whatIf.to_invalid), same: n(whatIf.unchanged) })}</p> : null}
          </Card>
          {blast ? <p className="text-sm text-slate-700" data-testid="blast">{t(locale, "geo.blast")}: {t(locale, "geo.blast.line", { zones: n(blast.zones), routes: n(blast.routes), outlets: n(blast.outlets), users: n(blast.users), devices: n(blast.devices) })}</p> : null}
          {density ? (
            <Card title={t(locale, "geo.density")} testId="density">
              <DataTable columns={denCols} rows={density.rows} rowKey={(r) => String(r.node.id)} empty={t(locale, "common.empty")} caption={t(locale, "geo.density")} />
            </Card>
          ) : null}
          {calibration ? (
            <Card title={t(locale, "geo.calibration")} testId="calibration">
              <DataTable columns={calCols} rows={calibration.rows} rowKey={(r) => `${r.territory_id}-${r.geo_class ?? ""}`} empty={t(locale, "common.empty")} caption={t(locale, "geo.calibration")} />
            </Card>
          ) : null}
        </>
      )}
    </div>
  );
}
