// F-ADM-013 operating-parameter console (and P3 rules, P4 switches): keys by area with typed validation, a risk class,
// a mandatory reason, the audit row written by the API and the reach widget. One component, three entry pages.
import Link from "next/link";
import { Card, PageHeading, qs } from "@/components/admin/kit/page";
import { configInputText } from "@/lib/admin/config";
import type { ConfigKey, ConfigReach, ResolvedConfigValue } from "@/lib/admin/types";
import { t, type Locale, type MessageKey } from "@/lib/i18n";
import { ConfigSetForm } from "./config-set-form";
import { ReachWidget } from "./reach-widget";

export interface ConsoleProps {
  locale: Locale;
  basePath: string;
  titleKey: MessageKey;
  introKey?: MessageKey;
  keys: ConfigKey[];
  areas: string[];
  area: string | undefined;
  values: Record<string, ResolvedConfigValue>;
  reach: ConfigReach | null;
  canWrite: boolean;
  /** Operational switches ask for a duration. */
  switches?: boolean;
  /** Longest switch duration in hours (cfg.sys.break_glass_max_h is the registry's own bound for the strongest switch). */
  maxSwitchHours?: number;
}

const describe = (k: ConfigKey, locale: Locale) => (locale === "bn" && k.description_bn ? k.description_bn : k.description_en);

export function ConfigConsoleView({ locale, basePath, titleKey, introKey, keys, areas, area, values, reach, canWrite, switches, maxSwitchHours = 72 }: ConsoleProps) {
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, titleKey)} intro={introKey ? t(locale, introKey) : undefined} />
      <Card>
        <ReachWidget locale={locale} reach={reach} />
      </Card>
      <nav className="flex flex-wrap gap-2" aria-label={t(locale, "cfgk.areas")}>
        <Link href={basePath} className={`rounded border px-3 py-1 text-sm ${area === undefined ? "border-brand-600 bg-brand-50" : "border-slate-300 bg-white"}`}>
          {t(locale, "common.all")}
        </Link>
        {areas.map((a) => (
          <Link key={a} href={`${basePath}${qs({ area: a })}`} className={`rounded border px-3 py-1 text-sm ${area === a ? "border-brand-600 bg-brand-50" : "border-slate-300 bg-white"}`}>
            {a}
          </Link>
        ))}
      </nav>
      {keys.length === 0 ? <p className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">{t(locale, "common.empty")}</p> : null}
      <ul className="space-y-3" data-testid="key-list">
        {keys.map((k) => {
          const cur = values[k.key];
          const value = cur ? cur.value : k.default_value;
          return (
            <li key={k.key} data-testid={`key-${k.key}`}>
              <Card>
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <h2 className="font-mono text-base font-semibold">{k.key}</h2>
                  <span className="flex gap-2 text-xs">
                    <span className="rounded bg-slate-100 px-2 py-0.5" data-testid="risk">
                      {t(locale, `cfgc.risk.${k.risk_class}` as MessageKey)}
                    </span>
                    <span className="rounded bg-slate-100 px-2 py-0.5">{t(locale, `cfgk.effect.${k.effect}` as MessageKey)}</span>
                  </span>
                </div>
                <p className="text-sm text-slate-700">{describe(k, locale)}</p>
                <p className="text-sm">
                  {t(locale, "cfgk.current")}: <code className="rounded bg-slate-100 px-1.5 py-0.5" data-testid="current-value">{configInputText(value) || "∅"}</code>{" "}
                  <span className="text-slate-500">({cur ? t(locale, `cfgk.from.${cur.scope_type}` as MessageKey) : t(locale, "cfgk.from.default")})</span>
                </p>
                {canWrite ? (
                  <details>
                    <summary className="cursor-pointer text-sm text-brand-700 underline">{t(locale, "common.edit")}</summary>
                    <div className="mt-2">
                      <ConfigSetForm
                        keyName={k.key}
                        valueType={k.value_type}
                        bounds={k.bounds}
                        scopeLevels={k.scope_levels}
                        current={value}
                        label={t(locale, "cfgc.col.value")}
                        futureOnly={k.future_dated_only}
                        duration={switches || k.kind === "O" ? { maxHours: maxSwitchHours } : undefined}
                      />
                    </div>
                  </details>
                ) : null}
              </Card>
            </li>
          );
        })}
      </ul>
      {canWrite ? null : <p className="text-sm text-slate-600">{t(locale, "cfgc.read_only")}</p>}
    </div>
  );
}
