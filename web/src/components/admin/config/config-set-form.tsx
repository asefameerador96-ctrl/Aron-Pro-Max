"use client";
// Edit one config key at one scope: typed input checked against the registry bounds, mandatory reason, optional
// effective time, result shown as the change status (applied, scheduled or waiting for a second approver).
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import { configInputText, parseConfigInput, type ValueType } from "@/lib/admin/config";
import type { ConfigBounds, ConfigScopeType } from "@/lib/admin/types";
import type { MessageKey } from "@/lib/i18n";
import { Field, inputClass } from "../kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "../kit/reason-field";

export interface ConfigSetFormProps {
  keyName: string;
  valueType: ValueType;
  bounds: ConfigBounds | null;
  /** Scope levels the registry allows for this key. */
  scopeLevels: ConfigScopeType[];
  /** Fixed scope (e.g. the geofence page picked a zone); when absent the user chooses level and id. */
  scope?: { type: ConfigScopeType; id: number };
  current: unknown;
  /** Value labels of an enum key, by value. */
  optionLabels?: Record<string, string>;
  label: string;
  /** Show the optional "effective from" input (future-dated keys require it). */
  futureOnly?: boolean;
  /** `{ period: ... }` style keys that expire (operational switches) pass a fixed end offset in minutes. */
  testId?: string;
}

const STATUS_KEY: Record<string, MessageKey> = {
  applied: "cfgc.status.applied",
  scheduled: "cfgc.status.scheduled",
  pending_approval: "cfgc.status.pending_approval",
  rejected: "cfgc.status.rejected",
  cancelled: "cfgc.status.cancelled",
  expired: "cfgc.status.expired",
  reverted: "cfgc.status.reverted",
};

export function ConfigSetForm({ keyName, valueType, bounds, scopeLevels, scope, current, optionLabels, label, futureOnly, testId = "config-set-form" }: ConfigSetFormProps) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [raw, setRaw] = useState(valueType === "bool" ? String(current === true) : configInputText(current));
  const [level, setLevel] = useState<ConfigScopeType>(scope?.type ?? scopeLevels[0] ?? "global");
  const [scopeId, setScopeId] = useState(scope ? String(scope.id) : level === "global" ? "0" : "");
  const [from, setFrom] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBanner(null);
    const local: Record<string, string> = {};
    const parsed = parseConfigInput(valueType, raw, bounds);
    if (!parsed.ok) local.value = t(`cfgc.error.${parsed.code}` as MessageKey);
    const sType = scope?.type ?? level;
    const sId = scope ? scope.id : sType === "global" ? 0 : Number(scopeId);
    if (!Number.isInteger(sId) || sId < 0 || (sType !== "global" && sId < 1)) local.scope_id = t("error.field.invalid");
    if (futureOnly && from === "") local.from = t("error.field.required");
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) local.reason = t("admin.reason.too_short");
    setErrors(local);
    if (Object.keys(local).length || !parsed.ok) return;
    const item: Record<string, unknown> = { key: keyName, scope_type: sType, scope_id: sId, value: parsed.value };
    if (from) item.effective_from = new Date(`${from}T00:00:00+06:00`).toISOString();
    setBusy(true);
    try {
      const res = await fetch("/api/bff/admin-op", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({ op: "config.change", body: { changes: [item] }, reason: reason.trim() }),
      });
      const data = (await res.json().catch(() => ({}))) as { data?: { status?: string } | null } & Partial<Problem>;
      if (res.ok) {
        const status = data.data?.status ?? "applied";
        setBanner({ kind: "ok", text: `${t("cfgc.change.sent")} ${t(STATUS_KEY[status] ?? "cfgc.status.applied")}` });
        setReason("");
        router.refresh();
        return;
      }
      setBanner({ kind: "error", text: problem(data.code) });
    } catch {
      setBanner({ kind: "error", text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  const input =
    valueType === "bool" ? (
      <input id={`v-${keyName}`} type="checkbox" checked={raw === "true"} onChange={(e) => setRaw(e.target.checked ? "true" : "false")} className="h-4 w-4" />
    ) : valueType === "enum" && bounds?.enum ? (
      <select id={`v-${keyName}`} value={raw} onChange={(e) => setRaw(e.target.value)} className={inputClass}>
        {bounds.enum.map((o) => (
          <option key={o} value={o}>
            {optionLabels?.[o] ?? o}
          </option>
        ))}
      </select>
    ) : valueType === "json" || valueType === "text" ? (
      <textarea id={`v-${keyName}`} value={raw} onChange={(e) => setRaw(e.target.value)} rows={3} className={inputClass} />
    ) : (
      <input id={`v-${keyName}`} value={raw} onChange={(e) => setRaw(e.target.value)} type={valueType === "time" ? "time" : "text"} inputMode={["int", "number", "pct", "money_mtk"].includes(valueType) ? "decimal" : undefined} aria-invalid={errors.value ? true : undefined} className={inputClass} />
    );
  const rangeHint = bounds && (bounds.min != null || bounds.max != null) ? `${bounds.min ?? "…"} – ${bounds.max ?? "…"}` : undefined;

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm" data-testid={testId} data-key={keyName}>
      <Field label={label} htmlFor={`v-${keyName}`} hint={rangeHint} error={errors.value}>
        {input}
      </Field>
      {scope ? null : (
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={t("cfgc.scope.type")} htmlFor={`s-${keyName}`}>
            <select id={`s-${keyName}`} value={level} onChange={(e) => { setLevel(e.target.value as ConfigScopeType); if (e.target.value === "global") setScopeId("0"); }} className={inputClass}>
              {scopeLevels.map((l) => (
                <option key={l} value={l}>
                  {t(`cfgc.scope.${l}` as MessageKey)}
                </option>
              ))}
            </select>
          </Field>
          {level === "global" ? null : (
            <Field label={t("cfgc.scope.id")} htmlFor={`i-${keyName}`} error={errors.scope_id}>
              <input id={`i-${keyName}`} value={scopeId} onChange={(e) => setScopeId(e.target.value)} inputMode="numeric" className={inputClass} />
            </Field>
          )}
        </div>
      )}
      {futureOnly ? (
        <Field label={t("cfgc.effective_from")} htmlFor={`f-${keyName}`} required error={errors.from} hint={t("cfgc.effective_from.hint")}>
          <input id={`f-${keyName}`} type="date" value={from} onChange={(e) => setFrom(e.target.value)} className={inputClass} />
        </Field>
      ) : null}
      <ReasonField value={reason} onChange={setReason} error={errors.reason} id={`r-${keyName}`} />
      {banner ? (
        <p role={banner.kind === "error" ? "alert" : "status"} data-testid={banner.kind === "ok" ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.kind === "ok" ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>
          {banner.text}
        </p>
      ) : null}
      <button type="submit" disabled={busy} className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
        {t("common.save")}
      </button>
    </form>
  );
}
