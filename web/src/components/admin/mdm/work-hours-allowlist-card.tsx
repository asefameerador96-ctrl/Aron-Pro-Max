"use client";
// D-NIGHT-MDM-ALLOWLIST-01: the "Work-hours allow-list" card of the MDM policy editor. Controlled by the editor, which
// sends it as `work_hours_allowlist`. Package ids are typed or picked from presets until the packages catalogue
// (contract 1.9 queue row 6) lets the picker list the phone's own apps.
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { ALLOWLIST_MAX, ALLOWLIST_PRESETS, WEEKDAY_KEYS, allowlistPreview, type MdmWorkHoursAllowlist } from "@/lib/admin/mdm";
import { PACKAGE_EXAMPLE, PACKAGE_MAX_LEN, PACKAGE_PATTERN } from "@/lib/admin/packages";
import type { MessageKey } from "@/lib/i18n";
import { Field, inputClass } from "../kit/field";

export function WorkHoursAllowlistCard({
  value,
  onChange,
  readOnly,
  errors,
  now = new Date(),
}: {
  value: MdmWorkHoursAllowlist;
  onChange: (v: MdmWorkHoursAllowlist) => void;
  readOnly: boolean;
  errors: Record<string, string>;
  now?: Date;
}) {
  const { t } = useI18n();
  const [draft, setDraft] = useState("");
  const [draftError, setDraftError] = useState<string | undefined>();
  const set = (patch: Partial<MdmWorkHoursAllowlist>) => onChange({ ...value, ...patch });
  const setSchedule = (patch: Partial<MdmWorkHoursAllowlist["schedule"]>) => set({ schedule: { ...value.schedule, ...patch } });
  const days = value.schedule.days ?? [];
  const full = value.packages.length >= ALLOWLIST_MAX;

  function add(pkg: string) {
    const v = pkg.trim();
    if (!PACKAGE_PATTERN.test(v) || v.length > PACKAGE_MAX_LEN) return setDraftError(t("ab.invalid"));
    if (value.packages.includes(v)) return setDraftError(t("ab.duplicate"));
    if (full) return setDraftError(t("mdm.al.err.too_many"));
    setDraftError(undefined);
    set({ packages: [...value.packages, v] });
    setDraft("");
  }

  const preview = allowlistPreview(value, now);
  const previewText =
    preview.kind === "blocked" ? t("mdm.al.preview.blocked", { from: preview.from, to: preview.to, n: preview.n }) : t(`mdm.al.preview.${preview.kind === "free_today" ? "free" : preview.kind}` as MessageKey);

  return (
    <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm" data-testid="mdm-allowlist">
      <h2 className="text-lg font-semibold">{t("mdm.al.title")}</h2>
      <p className="text-sm text-slate-600">{t("mdm.al.hint")}</p>
      <label className="flex items-center gap-2 text-sm font-medium">
        <input type="checkbox" disabled={readOnly} checked={value.enabled} onChange={(e) => set({ enabled: e.target.checked })} data-testid="mdm-al-on" />
        {t("mdm.al.on")}
      </label>

      <div className="space-y-2">
        <h3 className="text-sm font-semibold">{t("mdm.al.packages", { n: value.packages.length, max: ALLOWLIST_MAX })}</h3>
        <ul className="flex flex-wrap gap-2" data-testid="mdm-al-packages">
          {value.packages.map((p) => (
            <li key={p} className="flex items-center gap-1 rounded-full border border-slate-300 px-2 py-0.5 text-xs">
              <code>{p}</code>
              {readOnly ? null : (
                <button type="button" aria-label={`${t("mdm.remove")} ${p}`} onClick={() => set({ packages: value.packages.filter((x) => x !== p) })} className="px-1 text-slate-600 hover:text-[var(--danger)]">
                  ×
                </button>
              )}
            </li>
          ))}
          {value.packages.length === 0 ? <li className="text-xs text-slate-500">{t("common.none")}</li> : null}
        </ul>
        {errors.allowlist_packages ? <p role="alert" className="text-xs font-medium text-red-700">{errors.allowlist_packages}</p> : null}
        {readOnly ? null : (
          <>
            <div className="flex flex-wrap gap-1" aria-label={t("mdm.al.presets")}>
              {ALLOWLIST_PRESETS.filter((s) => !value.packages.includes(s.pkg)).map((s) => (
                <button key={s.pkg} type="button" disabled={full} onClick={() => add(s.pkg)} className="rounded-full border border-dashed border-slate-300 px-2 py-0.5 text-xs hover:bg-slate-100 disabled:opacity-50" data-testid={`mdm-al-preset-${s.pkg}`}>
                  + {s.label}
                </button>
              ))}
            </div>
            <div className="flex flex-wrap items-start gap-2">
              <div className="min-w-64 flex-1">
                <Field label={t("mdm.al.add")} htmlFor="mdm-al-pkg" hint={t("mdm.al.add.hint")} error={draftError}>
                  <input
                    id="mdm-al-pkg"
                    value={draft}
                    placeholder={PACKAGE_EXAMPLE}
                    onChange={(e) => setDraft(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") {
                        e.preventDefault();
                        add(draft);
                      }
                    }}
                    className={inputClass}
                    autoComplete="off"
                  />
                </Field>
              </div>
              <button type="button" onClick={() => add(draft)} className="mt-6 rounded border border-slate-300 px-3 py-2 text-sm hover:bg-slate-100" data-testid="mdm-al-add">
                {t("mdm.add")}
              </button>
            </div>
          </>
        )}
      </div>

      <fieldset className="space-y-2">
        <legend className="text-sm font-semibold">{t("mdm.al.schedule")}</legend>
        {(["calendar", "window"] as const).map((m) => (
          <label key={m} className="flex items-center gap-2 text-sm">
            <input type="radio" name="mdm-al-mode" value={m} disabled={readOnly} checked={value.schedule.mode === m} onChange={() => setSchedule({ mode: m, days: m === "window" && !days.length ? [7, 1, 2, 3, 4] : days })} data-testid={`mdm-al-mode-${m}`} />
            {t(`mdm.al.mode.${m}` as MessageKey)}
          </label>
        ))}
        {value.schedule.mode === "window" ? (
          <div className="flex flex-wrap gap-2" role="group" aria-label={t("mdm.al.mode.window")}>
            {WEEKDAY_KEYS.map((wd, i) => {
              const iso = i + 1;
              const on = days.includes(iso);
              return (
                <button
                  key={wd}
                  type="button"
                  disabled={readOnly}
                  aria-pressed={on}
                  data-testid={`mdm-al-day-${iso}`}
                  onClick={() => setSchedule({ days: on ? days.filter((d) => d !== iso) : [...days, iso].sort((a, b) => a - b) })}
                  className={`rounded-full border px-3 py-1 text-xs ${on ? "border-brand-600 bg-brand-600 text-white" : "border-slate-300 bg-white hover:bg-slate-100"}`}
                >
                  {t(`mdm.wd.${wd}` as MessageKey)}
                </button>
              );
            })}
          </div>
        ) : null}
        {errors.allowlist_days ? <p role="alert" className="text-xs font-medium text-red-700">{errors.allowlist_days}</p> : null}
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label={t("mdm.al.start")} htmlFor="mdm-al-start" error={errors.allowlist_hours}>
            <input id="mdm-al-start" type="time" step={60} value={value.schedule.start} disabled={readOnly} onChange={(e) => setSchedule({ start: e.target.value })} className={inputClass} />
          </Field>
          <Field label={t("mdm.al.end")} htmlFor="mdm-al-end">
            <input id="mdm-al-end" type="time" step={60} value={value.schedule.end} disabled={readOnly} onChange={(e) => setSchedule({ end: e.target.value })} className={inputClass} />
          </Field>
          <Field label={t("mdm.al.grace")} htmlFor="mdm-al-grace" error={errors.allowlist_grace}>
            <input
              id="mdm-al-grace"
              inputMode="numeric"
              value={String(value.grace_min ?? 5)}
              disabled={readOnly}
              onChange={(e) => set({ grace_min: e.target.value === "" ? 0 : Number(e.target.value.replace(/\D/g, "").slice(0, 2)) })}
              className={inputClass}
            />
          </Field>
        </div>
      </fieldset>

      <div className={`rounded-md border p-3 text-sm ${preview.kind === "blocked" ? "border-[var(--danger)] bg-red-50" : "border-slate-200 bg-slate-50"}`} data-testid="mdm-al-preview" aria-live="polite">
        <p className="font-semibold">{previewText}</p>
        {preview.kind === "blocked" && preview.calendar ? <p className="text-xs text-slate-700">{t("mdm.al.preview.calendar")}</p> : null}
        {value.enabled ? <p className="text-xs text-slate-600">{t("mdm.al.preview.delay")}</p> : null}
      </div>
    </section>
  );
}
