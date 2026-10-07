// F-ADM-055 Config page P18: feature flags and waves = the cfg.flag.* keys, shown as a matrix of flag by scope, edited by scope.
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { Card, PageHeading } from "@/components/admin/kit/page";
import { configInputText } from "@/lib/admin/config";
import type { ConfigKey, ConfigValue } from "@/lib/admin/types";
import { formatDateTime, t, type Locale, type MessageKey } from "@/lib/i18n";
import { ConfigSetForm } from "./config-set-form";

export interface FlagRow {
  key: ConfigKey;
  values: ConfigValue[];
}

export function FlagsView({ locale, flags, canWrite }: { locale: Locale; flags: FlagRow[]; canWrite: boolean }) {
  const cols: Column<ConfigValue>[] = [
    { key: "s", header: t(locale, "cfgc.col.scope"), render: (v) => `${t(locale, `cfgc.scope.${v.scope_type}` as MessageKey)}${v.scope_type === "global" ? "" : ` #${v.scope_id}`}` },
    { key: "v", header: t(locale, "cfgc.col.value"), render: (v) => (v.value === true ? t(locale, "fl.on") : v.value === false ? t(locale, "fl.off") : configInputText(v.value)) },
    { key: "f", header: t(locale, "cfgc.from"), render: (v) => formatDateTime(locale, v.effective_from) },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "fl.title")} intro={t(locale, "fl.intro")} />
      <Card title={t(locale, "fl.matrix")} testId="flag-matrix">
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead className="bg-slate-50 text-left">
              <tr>
                <th className="px-2 py-1">{t(locale, "fl.flag")}</th>
                <th className="px-2 py-1">{t(locale, "fl.default")}</th>
                <th className="px-2 py-1">{t(locale, "fl.overrides")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {flags.map((f) => (
                <tr key={f.key.key} data-testid={`flag-row-${f.key.key}`}>
                  <td className="px-2 py-1 font-mono text-xs">{f.key.key}</td>
                  <td className="px-2 py-1">{f.key.default_value === true ? t(locale, "fl.on") : f.key.default_value === false ? t(locale, "fl.off") : configInputText(f.key.default_value)}</td>
                  <td className="px-2 py-1">{f.values.length}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
      {flags.map((f) => (
        <Card key={f.key.key} title={f.key.key} testId={`flag-${f.key.key}`}>
          <p className="text-sm text-slate-700">{locale === "bn" && f.key.description_bn ? f.key.description_bn : f.key.description_en}</p>
          <DataTable columns={cols} rows={f.values} rowKey={(v) => String(v.id)} empty={t(locale, "fl.no_overrides")} caption={f.key.key} />
          {canWrite ? <ConfigSetForm keyName={f.key.key} valueType={f.key.value_type} bounds={f.key.bounds} scopeLevels={f.key.scope_levels} current={f.key.default_value} label={t(locale, "fl.value")} testId={`flag-form-${f.key.key}`} /> : null}
        </Card>
      ))}
      {flags.length === 0 ? <p className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">{t(locale, "common.empty")}</p> : null}
    </div>
  );
}
