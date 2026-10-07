"use client";
// A form that runs one whitelisted admin operation (lib/admin/ops.ts) through the BFF, with the mandatory reason.
// Props are plain data so a server page can render it; every label is resolved on the server (no text here).
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import type { OpKey, TeamOpKey } from "@/lib/admin/ops";
import type { MessageKey } from "@/lib/i18n";
import { Field, inputClass } from "./field";
import { ReasonField, REASON_MIN_LENGTH } from "./reason-field";

export interface OpFieldDef {
  name: string;
  label: string;
  kind: "text" | "textarea" | "json" | "int" | "number" | "enum" | "date" | "checkbox";
  required?: boolean;
  hint?: string;
  maxLength?: number;
  options?: { value: string; label: string }[];
  /** Initial value as text ("true"/"false" for checkbox). */
  initial?: string;
  /** Empty input becomes null instead of being left out. */
  nullable?: boolean;
  /** `int` fields: the value is multiplied by this before sending (e.g. 1000 to turn taka into milli-taka). */
  scale?: number;
  /** Smallest allowed value after scaling. */
  min?: number;
  /** Lower-case the text before checking and sending (a pasted fingerprint). */
  lowercase?: boolean;
  /** Largest allowed value after scaling (a size gate, for example). */
  max?: number;
  /** The text must match this regular expression (a checksum, for example). */
  pattern?: string;
}

export interface OpResult {
  data: Record<string, unknown> | null;
}

interface Props {
  op: OpKey | TeamOpKey;
  /** BFF endpoint: the admin whitelist by default, "/api/bff/team-op" for web-role pages. */
  endpoint?: string;
  /** Body members that get a fresh client UUID per attempt-set (idempotency: a retry reuses it, a success renews it). */
  uuidMembers?: string[];
  params?: Record<string, string>;
  fields: OpFieldDef[];
  /** Fixed body members merged under the typed values (e.g. a scope the page already chose). */
  fixed?: Record<string, string | number | boolean | null>;
  /** If-Match version of the row being edited. */
  version?: number;
  submitLabel: string;
  /** Hide the reason box for an operation without one (the whitelist decides; the BFF ignores a reason it cannot place). */
  noReason?: boolean;
  successKey?: MessageKey;
  /** Show this member of the response next to the success text (e.g. the issued OTP). */
  resultField?: string;
  /** Clear the form after a success. */
  resetOnSuccess?: boolean;
  testId?: string;
}

function fieldMessage(t: ReturnType<typeof useI18n>["t"], code: string): string {
  const key = `error.field.${code}` as MessageKey;
  return t(["required", "too_short", "too_long", "unknown_member"].includes(code) ? key : "error.field.invalid");
}

function toValue(f: OpFieldDef, raw: string): unknown {
  if (f.kind === "checkbox") return raw === "true";
  if (raw.trim() === "") return f.nullable ? null : undefined;
  if (f.kind === "int") return f.scale || /^-?\d+$/.test(raw.trim()) ? Math.round(Number(raw) * (f.scale ?? 1)) : Symbol.for("invalid-int");
  if (f.kind === "number") return Number(raw);
  if (f.kind === "json") {
    try {
      return JSON.parse(raw);
    } catch {
      return Symbol.for("invalid-json");
    }
  }
  return f.lowercase ? raw.trim().toLowerCase() : raw.trim();
}

export function OpForm({ op, endpoint, uuidMembers, params, fields, fixed, version, submitLabel, noReason, successKey, resultField, resetOnSuccess = true, testId = "op-form" }: Props) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const initial = Object.fromEntries(fields.map((f) => [f.name, f.initial ?? (f.kind === "checkbox" ? "false" : f.kind === "enum" && f.required ? (f.options?.[0]?.value ?? "") : "")]));
  const [values, setValues] = useState<Record<string, string>>(initial);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ kind: "ok" | "error"; text: string } | null>(null);
  const [ver, setVer] = useState(version);
  const [uuids, setUuids] = useState<Record<string, string>>(() => Object.fromEntries((uuidMembers ?? []).map((m) => [m, crypto.randomUUID()])));

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setErrors({});
    setBanner(null);
    const local: Record<string, string> = {};
    const body: Record<string, unknown> = { ...fixed, ...uuids };
    for (const f of fields) {
      const raw = values[f.name] ?? "";
      if (f.required && f.kind !== "checkbox" && raw.trim() === "") local[f.name] = t("error.field.required");
      if ((f.kind === "int" || f.kind === "number") && raw.trim() !== "" && !Number.isFinite(Number(raw))) local[f.name] = t("error.field.invalid");
      const v = toValue(f, raw);
      if (v === Symbol.for("invalid-int")) local[f.name] = t("error.field.invalid");
      if (typeof v === "number" && f.min !== undefined && v < f.min) local[f.name] = t("cfgc.error.too_small");
      if (v === Symbol.for("invalid-json")) local[f.name] = t("error.field.invalid");
      if (typeof v === "number" && f.max !== undefined && v > f.max) local[f.name] = t("cfgc.error.too_big");
      if (f.pattern && raw.trim() !== "" && !new RegExp(f.pattern).test(f.lowercase ? raw.trim().toLowerCase() : raw.trim())) local[f.name] = t("error.field.invalid");
      if (v !== undefined && typeof v !== "symbol") body[f.name] = v;
    }
    if (!noReason && Array.from(reason.trim()).length < REASON_MIN_LENGTH) local.reason = t("admin.reason.too_short");
    if (Object.keys(local).length) {
      setErrors(local);
      return;
    }
    setBusy(true);
    try {
      const res = await fetch(endpoint ?? "/api/bff/admin-op", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({ op, params, body, ...(noReason ? {} : { reason: reason.trim() }), ...(ver !== undefined ? { version: ver } : {}) }),
      });
      const data = (await res.json().catch(() => ({}))) as { data?: Record<string, unknown> | null } & Partial<Problem>;
      if (res.ok) {
        const shown = resultField && data.data ? String(resultField.split(".").reduce<unknown>((o, k) => (o && typeof o === "object" ? (o as Record<string, unknown>)[k] : undefined), data.data) ?? "") : "";
        const newVersion = data.data && typeof data.data.version === "number" ? data.data.version : undefined;
        if (newVersion !== undefined) setVer(newVersion);
        setBanner({ kind: "ok", text: `${t(successKey ?? "admin.save.ok")}${shown ? ` ${shown}` : ""}` });
        setReason("");
        if (resetOnSuccess) setValues(initial);
        setUuids(Object.fromEntries((uuidMembers ?? []).map((m) => [m, crypto.randomUUID()])));
        router.refresh();
        return;
      }
      const fieldErrors: Record<string, string> = {};
      for (const err of data.errors ?? []) {
        const name = err.pointer.startsWith("/body/") ? err.pointer.slice("/body/".length) : err.pointer.replace(/^\//, "");
        fieldErrors[name === "reason" || fields.some((f) => f.name === name) ? name : ""] = fieldMessage(t, err.code);
      }
      delete fieldErrors[""];
      setErrors(fieldErrors);
      setBanner({ kind: "error", text: problem(data.code) });
    } catch {
      setBanner({ kind: "error", text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  const set = (name: string, v: string) => setValues((s) => ({ ...s, [name]: v }));
  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4 rounded-lg border border-slate-200 bg-white p-4 shadow-sm" data-testid={testId}>
      {fields.map((f) => (
        <Field key={f.name} label={f.label} htmlFor={`f-${f.name}`} required={f.required} hint={f.hint} error={errors[f.name]}>
          {f.kind === "enum" ? (
            <select id={`f-${f.name}`} name={f.name} value={values[f.name] ?? ""} onChange={(e) => set(f.name, e.target.value)} className={inputClass}>
              {!f.required ? <option value="">{t("common.none")}</option> : null}
              {f.options?.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          ) : f.kind === "textarea" || f.kind === "json" ? (
            <textarea id={`f-${f.name}`} name={f.name} value={values[f.name] ?? ""} onChange={(e) => set(f.name, e.target.value)} maxLength={f.maxLength} rows={3} className={inputClass} />
          ) : f.kind === "checkbox" ? (
            <input id={`f-${f.name}`} name={f.name} type="checkbox" checked={values[f.name] === "true"} onChange={(e) => set(f.name, e.target.checked ? "true" : "false")} className="h-4 w-4" />
          ) : (
            <input
              id={`f-${f.name}`}
              name={f.name}
              type={f.kind === "date" ? "date" : "text"}
              value={values[f.name] ?? ""}
              onChange={(e) => set(f.name, e.target.value)}
              inputMode={f.kind === "int" ? "numeric" : f.kind === "number" ? "decimal" : undefined}
              maxLength={f.maxLength}
              aria-invalid={errors[f.name] ? true : undefined}
              className={inputClass}
            />
          )}
        </Field>
      ))}
      {noReason ? null : <ReasonField value={reason} onChange={setReason} error={errors.reason} />}
      {banner ? (
        <p role={banner.kind === "error" ? "alert" : "status"} data-testid={banner.kind === "ok" ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.kind === "ok" ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>
          {banner.text}
        </p>
      ) : null}
      <button type="submit" disabled={busy} className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
        {submitLabel}
      </button>
    </form>
  );
}
