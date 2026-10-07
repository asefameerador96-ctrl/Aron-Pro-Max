"use client";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { MessageKey } from "@/lib/i18n";
import { en } from "@/lib/i18n/messages-en";
import type { Problem } from "@/contract/types";
import { Field, inputClass } from "../kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "../kit/reason-field";
import type { FieldError, WriteRequest } from "./types";

export interface FormFieldDef {
  name: string;
  label: string;
  kind: "text" | "int" | "enum" | "date" | "mask" | "number";
  /** Translated section heading; consecutive fields with the same heading are grouped. */
  section?: string;
  /** Labels of the bits of a `mask` field, bit 0 first. */
  maskBits?: string[];
  required: boolean;
  nullable: boolean;
  maxLength?: number;
  options?: { value: string; label: string }[];
}

interface Props {
  mode: "create" | "update" | "action";
  slug: string;
  /** Key of the row action (mode "action"). */
  action?: string;
  /** false: the contract has no member for the reason here, so it is required but not stored (shown as a note). */
  reasonStored?: boolean;
  /** Label of the submit button (mode "action"). */
  submitLabel?: string;
  id?: string;
  version?: number;
  fields: FormFieldDef[];
  initial: Record<string, string>;
  listHref: string;
}

function fieldMessage(t: ReturnType<typeof useI18n>["t"], code: string): string {
  const key = `error.field.${code}` as MessageKey;
  return t(["required", "too_short", "too_long", "unknown_member", "past"].includes(code) ? key : "error.field.invalid");
}

/** The form of the CRUD generator: one input per writable field of the metadata, plus the mandatory reason. */
export function EntityForm({ mode, slug, action, submitLabel, reasonStored = true, id, version: initialVersion, fields, initial, listHref }: Props) {
  const { t, problem, dateTime } = useI18n();
  const router = useRouter();
  const [values, setValues] = useState<Record<string, string>>(initial);
  const [reason, setReason] = useState("");
  const [version, setVersion] = useState(initialVersion);
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [shown, setShown] = useState<Record<string, unknown> | null>(null);
  const [banner, setBanner] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setErrors({});
    setBanner(null);
    const sent: Record<string, unknown> = {};
    for (const f of fields) {
      const v = values[f.name] ?? "";
      if (mode === "update" && v === (initial[f.name] ?? "")) continue;
      if (mode !== "update" && v === "" && !f.required) continue;
      sent[f.name] = v;
    }
    const local: Record<string, string> = {};
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) local.reason = t("admin.reason.too_short");
    if (mode === "update" && Object.keys(sent).length === 0) {
      setBanner({ kind: "error", text: t("admin.no_changes") });
      if (local.reason) setErrors(local);
      return;
    }
    if (Object.keys(local).length) {
      setErrors(local);
      return;
    }
    const body: WriteRequest = { values: sent, reason: reason.trim(), ...(mode === "update" || (mode === "action" && version !== undefined) ? { version } : {}) };
    setBusy(true);
    try {
      const res = await fetch(mode === "create" ? `/api/bff/admin/${slug}` : mode === "action" ? `/api/bff/admin/${slug}/${id}/${action}` : `/api/bff/admin/${slug}/${id}`, {
        method: mode === "update" ? "PATCH" : "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify(body),
      });
      const data = (await res.json().catch(() => ({}))) as { row?: { version?: number }; shown?: Record<string, unknown> } & Partial<Problem>;
      if (res.ok) {
        if (data.shown && Object.values(data.shown).some((v) => v !== null)) {
          // One-time values (a temporary password): keep the page open until the user has copied them.
          setShown(data.shown);
          return;
        }
        if (mode !== "update") {
          router.push(listHref);
          router.refresh();
          return;
        }
        setVersion(data.row?.version ?? version);
        setReason("");
        setBanner({ kind: "ok", text: t("admin.save.ok") });
        router.refresh();
        return;
      }
      const fieldErrors: Record<string, string> = {};
      for (const err of (data.errors ?? []) as FieldError[]) {
        const name = err.pointer.startsWith("/values/") ? err.pointer.slice("/values/".length) : err.pointer === "/reason" ? "reason" : "";
        if (name) fieldErrors[name] = fieldMessage(t, err.code);
      }
      setErrors(fieldErrors);
      setBanner({ kind: "error", text: problem(data.code) });
    } catch {
      setBanner({ kind: "error", text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  if (shown) {
    return (
      <section role="status" data-testid="shown-once" className="space-y-3 rounded-lg border border-amber-300 bg-amber-50 p-4">
        <p className="font-semibold text-amber-900">{t("admin.shown_once")}</p>
        <dl className="space-y-1">
          {Object.entries(shown).map(([k, v]) => (
            <div key={k}>
              <dt className="text-xs text-slate-600">{`shown.${k}` in en ? t(`shown.${k}` as MessageKey) : k}</dt>
              <dd className="font-mono text-lg" data-testid={`once-${k}`}>
                {/^\d{4}-\d{2}-\d{2}T/.test(String(v)) ? dateTime(String(v)) : String(v ?? "")}
              </dd>
            </div>
          ))}
        </dl>
        <a href={listHref} className="inline-block rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700">
          {t("common.done")}
        </a>
      </section>
    );
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4 rounded-lg border border-slate-200 bg-white p-4 shadow-sm" data-testid="entity-form">
      {fields.map((f, idx) => (
        <div key={f.name} className="space-y-4">
          {f.section && f.section !== fields[idx - 1]?.section ? (
            <h2 className="border-b border-slate-200 pb-1 pt-2 text-lg font-semibold text-slate-800" data-testid={`section-${idx}`}>
              {f.section}
            </h2>
          ) : null}
        <Field label={f.label} htmlFor={`f-${f.name}`} required={f.required} error={errors[f.name]}>
          {f.kind === "enum" ? (
            <select id={`f-${f.name}`} name={f.name} aria-required={f.required || undefined} value={values[f.name] ?? ""} onChange={(e) => setValues({ ...values, [f.name]: e.target.value })} className={inputClass}>
              {f.nullable || !f.required ? <option value="">{t("common.none")}</option> : null}
              {f.options?.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          ) : f.kind === "mask" ? (
            <div id={`f-${f.name}`} role="group" aria-label={f.label} className="flex flex-wrap gap-3">
              {f.maskBits?.map((label, bit) => {
                const on = (Number(values[f.name] || 0) & (1 << bit)) !== 0;
                return (
                  <label key={bit} className="flex items-center gap-1 text-sm">
                    <input
                      type="checkbox"
                      name={`${f.name}-${bit}`}
                      checked={on}
                      onChange={(e) => setValues({ ...values, [f.name]: String((Number(values[f.name] || 0) & ~(1 << bit)) | (e.target.checked ? 1 << bit : 0)) })}
                    />
                    {label}
                  </label>
                );
              })}
            </div>
          ) : (
            <input
              id={`f-${f.name}`}
              type={f.kind === "date" ? "date" : "text"}
              name={f.name}
              value={values[f.name] ?? ""}
              onChange={(e) => setValues({ ...values, [f.name]: e.target.value })}
              aria-required={f.required || undefined}
              inputMode={f.kind === "int" ? "numeric" : f.kind === "number" ? "decimal" : undefined}
              maxLength={f.maxLength}
              aria-invalid={errors[f.name] ? true : undefined}
              className={inputClass}
            />
          )}
        </Field>
        </div>
      ))}
      <ReasonField value={reason} onChange={setReason} error={errors.reason} />
      {!reasonStored && mode !== "action" ? (
        <p data-testid="reason-not-stored" className="text-xs text-amber-800">
          {t("admin.reason.not_stored")}
        </p>
      ) : null}
      {banner ? (
        <p role={banner.kind === "error" ? "alert" : "status"} data-testid={banner.kind === "ok" ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.kind === "ok" ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>
          {banner.text}
        </p>
      ) : null}
      <div className="flex gap-3">
        <button type="submit" disabled={busy} className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
          {mode === "action" && submitLabel ? submitLabel : t(mode === "create" ? "common.create" : "common.save")}
        </button>
        <a href={listHref} className="rounded border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-100">
          {t("common.cancel")}
        </a>
      </div>
    </form>
  );
}
