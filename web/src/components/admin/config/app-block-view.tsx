// N-046 app-block list page: package names with groups, allow-list, always-allowed and the blocking settings.
import { PageHeading } from "@/components/admin/kit/page";
import type { ConfigKey, ResolvedConfigValue } from "@/lib/admin/types";
import { formatNumber, t, type Locale } from "@/lib/i18n";
import { ConfigSetForm } from "./config-set-form";
import { PackageListEditor } from "./package-list-editor";

const LISTS: { key: string; title: "ab.blocked" | "ab.allowed" | "ab.always" }[] = [
  { key: "cfg.device.blocked_packages", title: "ab.blocked" },
  { key: "cfg.device.allowed_packages", title: "ab.allowed" },
  { key: "cfg.device.always_allowed_packages", title: "ab.always" },
];
const SETTINGS = ["cfg.device.app_control_mode", "cfg.device.blocking_enabled", "cfg.device.blocking_hard_end_time", "cfg.device.blocking_working_days_only"];

export function AppBlockView({ locale, keys, values, version, canWrite }: { locale: Locale; keys: ConfigKey[]; values: Record<string, ResolvedConfigValue>; version: number | null; canWrite: boolean }) {
  const byKey = new Map(keys.map((k) => [k.key, k]));
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "ab.title")} intro={t(locale, "ab.intro")} />
      {version === null ? null : <p className="text-xs text-slate-500">{t(locale, "ab.version", { v: formatNumber(locale, version, { useGrouping: false }) })}</p>}
      {LISTS.map(({ key, title }) => {
        const k = byKey.get(key);
        if (!k) return null;
        const cur = values[key]?.value ?? k.default_value;
        return <PackageListEditor key={key} keyName={key} title={t(locale, title)} initial={Array.isArray(cur) ? cur.map(String) : []} max={k.bounds.max_items ?? 300} canWrite={canWrite} />;
      })}
      <section className="space-y-3" aria-label={t(locale, "ab.settings")}>
        <h2 className="text-lg font-semibold">{t(locale, "ab.settings")}</h2>
        {SETTINGS.map((name) => {
          const k = byKey.get(name);
          return k && canWrite ? <ConfigSetForm key={name} keyName={name} valueType={k.value_type} bounds={k.bounds} scopeLevels={k.scope_levels} scope={{ type: "global", id: 0 }} current={values[name]?.value ?? k.default_value} label={name} testId={`setting-${name}`} /> : null;
        })}
      </section>
    </div>
  );
}
