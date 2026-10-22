"use client";
// D-MDM-01 policy editor: allowed apps, weekday x time block grid (30-minute cells, Dhaka time), kiosk and location sampling.
// The grid is drawn by clicking or dragging; it becomes MdmBlockWindow[] (lib/admin/mdm.ts). Windows that name packages are kept as they are.
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import { ALLOWED_SUGGESTIONS, MAX_WINDOWS, SLOTS, WEEKDAY_KEYS, gridToWindows, packageWindows, validatePolicy, windowsToGrid, type Grid, type MdmPolicy } from "@/lib/admin/mdm";
import { PACKAGE_EXAMPLE, PACKAGE_MAX_LEN, PACKAGE_PATTERN } from "@/lib/admin/packages";
import type { MessageKey } from "@/lib/i18n";
import { Field, inputClass } from "../kit/field";
import { REASON_MIN_LENGTH } from "../kit/reason-field";

export function PolicyEditor({ initial, canWrite }: { initial: MdmPolicy | null; canWrite: boolean }) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [name, setName] = useState(initial?.name ?? "");
  const [description, setDescription] = useState(initial?.description ?? "");
  const [allowed, setAllowed] = useState<string[]>(initial?.allowed_packages ?? []);
  const [draft, setDraft] = useState("");
  const [grid, setGrid] = useState<Grid>(() => windowsToGrid(initial?.block_windows ?? []));
  const kept = packageWindows(initial?.block_windows ?? []);
  const [kioskOn, setKioskOn] = useState(initial?.kiosk.enabled ?? false);
  const [kioskPackages, setKioskPackages] = useState((initial?.kiosk.packages ?? []).join(", "));
  const [mode, setMode] = useState(initial?.location_sampling.mode ?? "on_demand");
  const [intervalMin, setIntervalMin] = useState(String(initial?.location_sampling.interval_min ?? 15));
  const [reason, setReason] = useState("");
  const [version, setVersion] = useState(initial?.version);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ kind: "ok" | "error"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const painting = useRef<boolean | null>(null);

  const paint = (d: number, s: number, v: boolean) => setGrid((g) => (g[d]![s] === v ? g : g.map((row, i) => (i === d ? row.map((c, j) => (j === s ? v : c)) : row))));
  const setDay = (d: number, v: boolean) => setGrid((g) => g.map((row, i) => (i === d ? row.map(() => v) : row)));

  function addPackage() {
    const v = draft.trim();
    if (!PACKAGE_PATTERN.test(v) || v.length > PACKAGE_MAX_LEN) return setErrors((e) => ({ ...e, draft: t("ab.invalid") }));
    if (allowed.includes(v)) return setErrors((e) => ({ ...e, draft: t("ab.duplicate") }));
    setErrors((e) => {
      const { draft: _d, ...rest } = e;
      return rest;
    });
    setAllowed([...allowed, v]);
    setDraft("");
  }

  const windows = [...gridToWindows(grid), ...kept];

  async function save() {
    const kp = kioskPackages.split(",").map((s) => s.trim()).filter(Boolean);
    const local = validatePolicy({ name, blockWindows: windows, kioskOn, kioskPackages: kp, samplingMode: mode, intervalMin });
    const out: Record<string, string> = {};
    for (const [k, c] of Object.entries(local)) out[k] = t(c === "too_many" ? "mdm.err.too_many_windows" : c === "too_long" ? "error.field.too_long" : c === "required" ? "error.field.required" : "error.field.invalid");
    if (kp.some((p) => !PACKAGE_PATTERN.test(p))) out.kiosk = t("ab.invalid");
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) out.reason = t("admin.reason.too_short");
    setErrors(out);
    if (Object.keys(out).length) return;
    const body = {
      name: name.trim(),
      description: description.trim() || null,
      allowed_packages: allowed,
      block_windows: windows,
      kiosk: { enabled: kioskOn, packages: kioskOn ? kp : [] },
      location_sampling: { mode, interval_min: mode === "interval" ? Number(intervalMin) : null },
      ...(initial ? { version } : {}),
    };
    setBusy(true);
    setBanner(null);
    try {
      const res = await fetch("/api/bff/admin-op", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify(initial ? { op: "mdm.policy.update", params: { policy_id: String(initial.policy_id) }, body, reason: reason.trim() } : { op: "mdm.policy.create", body, reason: reason.trim() }),
      });
      const data = (await res.json().catch(() => ({}))) as { data?: { version?: number } | null } & Partial<Problem>;
      if (res.ok) {
        if (!initial) {
          router.push("/admin/mdm/policies");
          router.refresh();
          return;
        }
        if (typeof data.data?.version === "number") setVersion(data.data.version);
        setBanner({ kind: "ok", text: t("admin.save.ok") });
        setReason("");
        router.refresh();
      } else setBanner({ kind: "error", text: problem(data.code) });
    } catch {
      setBanner({ kind: "error", text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  const ro = !canWrite;
  return (
    <div className="space-y-4" data-testid="mdm-policy-editor" onPointerUp={() => (painting.current = null)} onPointerLeave={() => (painting.current = null)}>
      <section className="grid gap-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm sm:grid-cols-2">
        <Field label={t("mdm.policy.name")} htmlFor="mdm-p-name" required error={errors.name}>
          <input id="mdm-p-name" value={name} maxLength={80} disabled={ro} onChange={(e) => setName(e.target.value)} className={inputClass} />
        </Field>
        <Field label={t("mdm.policy.description")} htmlFor="mdm-p-desc">
          <input id="mdm-p-desc" value={description} maxLength={300} disabled={ro} onChange={(e) => setDescription(e.target.value)} className={inputClass} />
        </Field>
      </section>

      <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm" data-testid="mdm-allowed">
        <h2 className="text-lg font-semibold">{t("mdm.policy.allowed")}</h2>
        <p className="text-sm text-slate-600">{t("mdm.policy.allowed.hint")}</p>
        <ul className="flex flex-wrap gap-2">
          {allowed.map((p) => (
            <li key={p} className="flex items-center gap-1 rounded-full border border-slate-300 px-2 py-0.5 text-xs">
              <code>{p}</code>
              {ro ? null : (
                <button type="button" aria-label={`${t("mdm.remove")} ${p}`} onClick={() => setAllowed(allowed.filter((x) => x !== p))} className="px-1 text-slate-600 hover:text-[var(--danger)]">
                  ×
                </button>
              )}
            </li>
          ))}
          {allowed.length === 0 ? <li className="text-xs text-slate-500">{t("common.none")}</li> : null}
        </ul>
        {ro ? null : (
          <>
            <div className="flex flex-wrap gap-1" aria-label={t("mdm.policy.suggested")}>
              {ALLOWED_SUGGESTIONS.filter((s) => !allowed.includes(s.pkg)).map((s) => (
                <button key={s.pkg} type="button" onClick={() => setAllowed([...allowed, s.pkg])} className="rounded-full border border-dashed border-slate-300 px-2 py-0.5 text-xs hover:bg-slate-100">
                  + {s.label}
                </button>
              ))}
            </div>
            <div className="flex flex-wrap items-start gap-2">
              <div className="min-w-64 flex-1">
                <Field label={t("mdm.policy.add_package")} htmlFor="mdm-p-pkg" error={errors.draft}>
                  <input id="mdm-p-pkg" value={draft} placeholder={PACKAGE_EXAMPLE} onChange={(e) => setDraft(e.target.value)} className={inputClass} autoComplete="off" />
                </Field>
              </div>
              <button type="button" onClick={addPackage} className="mt-6 rounded border border-slate-300 px-3 py-2 text-sm hover:bg-slate-100">
                {t("mdm.add")}
              </button>
            </div>
          </>
        )}
      </section>

      <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm" data-testid="mdm-schedule">
        <h2 className="text-lg font-semibold">{t("mdm.policy.schedule")}</h2>
        <p className="text-sm text-slate-600">{t("mdm.policy.schedule.hint")}</p>
        <div className="overflow-x-auto">
          <table className="border-collapse text-xs" aria-label={t("mdm.policy.schedule")}>
            <thead>
              <tr>
                <th className="w-24" />
                {Array.from({ length: 24 }, (_, h) => (
                  <th key={h} colSpan={2} scope="col" className="border-l border-slate-200 px-0.5 text-center font-normal text-slate-600">
                    {String(h).padStart(2, "0")}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {WEEKDAY_KEYS.map((wd, d) => (
                <tr key={wd}>
                  <th scope="row" className="whitespace-nowrap pr-2 text-left font-medium">
                    {t(`mdm.wd.${wd}` as MessageKey)}
                    {ro ? null : (
                      <>
                        {" "}
                        <button type="button" className="text-[10px] text-brand-700 underline" onClick={() => setDay(d, true)}>
                          {t("common.all")}
                        </button>{" "}
                        <button type="button" className="text-[10px] text-slate-600 underline" onClick={() => setDay(d, false)}>
                          {t("common.none")}
                        </button>
                      </>
                    )}
                  </th>
                  {Array.from({ length: SLOTS }, (_, s) => (
                    <td key={s} className="p-0">
                      <button
                        type="button"
                        disabled={ro}
                        data-testid={`cell-${d}-${s}`}
                        aria-pressed={grid[d]![s]}
                        aria-label={`${t(`mdm.wd.${wd}` as MessageKey)} ${String(Math.floor(s / 2)).padStart(2, "0")}:${s % 2 ? "30" : "00"}`}
                        onPointerDown={(e) => {
                          e.preventDefault();
                          painting.current = !grid[d]![s];
                          paint(d, s, painting.current);
                        }}
                        onPointerEnter={() => painting.current !== null && paint(d, s, painting.current)}
                        onKeyDown={(e) => {
                          if (e.key === " " || e.key === "Enter") {
                            e.preventDefault();
                            paint(d, s, !grid[d]![s]);
                          }
                        }}
                        className={`h-6 w-3 border border-slate-200 ${grid[d]![s] ? "bg-[var(--danger)]" : "bg-white hover:bg-slate-100"} ${s % 2 === 0 ? "border-l-slate-400" : ""}`}
                      />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="text-xs text-slate-600" data-testid="mdm-window-count">
          {t("mdm.policy.windows", { n: windows.length, max: MAX_WINDOWS })}
        </p>
        {errors.schedule ? <p role="alert" className="text-xs font-medium text-red-700">{errors.schedule}</p> : null}
        {kept.length ? (
          <p className="text-xs text-slate-600" data-testid="mdm-kept-windows">
            {t("mdm.policy.kept_windows", { n: kept.length })}
          </p>
        ) : null}
      </section>

      <section className="grid gap-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm sm:grid-cols-2">
        <div className="space-y-2">
          <h2 className="text-lg font-semibold">{t("mdm.policy.kiosk")}</h2>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" disabled={ro} checked={kioskOn} onChange={(e) => setKioskOn(e.target.checked)} data-testid="mdm-kiosk" />
            {t("mdm.policy.kiosk.on")}
          </label>
          {kioskOn ? (
            <Field label={t("mdm.policy.kiosk.packages")} htmlFor="mdm-p-kiosk" hint={t("mdm.policy.kiosk.hint")} error={errors.kiosk}>
              <input id="mdm-p-kiosk" value={kioskPackages} disabled={ro} onChange={(e) => setKioskPackages(e.target.value)} className={inputClass} />
            </Field>
          ) : null}
        </div>
        <div className="space-y-2">
          <h2 className="text-lg font-semibold">{t("mdm.policy.location")}</h2>
          <Field label={t("mdm.policy.location.mode")} htmlFor="mdm-p-mode" hint={t(`mdm.policy.location.${mode}` as MessageKey)}>
            <select id="mdm-p-mode" value={mode} disabled={ro} onChange={(e) => setMode(e.target.value as typeof mode)} className={inputClass}>
              {(["off", "on_demand", "interval"] as const).map((m) => (
                <option key={m} value={m}>
                  {t(`mdm.policy.location.${m}.label` as MessageKey)}
                </option>
              ))}
            </select>
          </Field>
          {mode === "interval" ? (
            <Field label={t("mdm.policy.location.interval_min")} htmlFor="mdm-p-interval" error={errors.interval}>
              <input id="mdm-p-interval" value={intervalMin} inputMode="numeric" disabled={ro} onChange={(e) => setIntervalMin(e.target.value)} className={inputClass} />
            </Field>
          ) : null}
        </div>
      </section>

      {ro ? (
        <p className="text-xs text-slate-600" data-testid="mdm-readonly">
          {t("mdm.readonly")}
        </p>
      ) : (
        <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
          <Field label={t("admin.reason.label")} htmlFor="mdm-p-reason" required hint={t("admin.reason.hint")} error={errors.reason}>
            <textarea id="mdm-p-reason" rows={2} maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} className={inputClass} />
          </Field>
          <button type="button" disabled={busy} onClick={save} data-testid="mdm-policy-save" className="rounded-full bg-brand-600 px-5 py-2.5 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
            {t("common.save")}
          </button>
          {banner ? (
            <p role={banner.kind === "error" ? "alert" : "status"} data-testid={banner.kind === "ok" ? "form-ok" : "form-error"} className={`text-sm ${banner.kind === "ok" ? "text-[var(--success)]" : "text-[var(--danger)]"}`}>
              {banner.text}
            </p>
          ) : null}
        </section>
      )}
    </div>
  );
}
