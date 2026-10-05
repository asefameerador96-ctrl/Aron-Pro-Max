"use client";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { MessageKey } from "@/lib/i18n";
import type { Problem } from "@/contract/types";
import { Field, inputClass } from "../kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "../kit/reason-field";
import type { FieldError, WriteRequest } from "./types";

export interface FormFieldDef {
  name: string;
  label: string;
  kind: "text" | "int" | "enum";
  required: boolean;
  nullable: boolean;
  maxLength?: number;
  options?: { value: string; label: string }[];
}

interface Props {
  mode: "create" | "update";
  slug: string;
  id?: string;
  version?: number;
  fields: FormFieldDef[];
  initial: Record<string, string>;
  listHref: string;
}

function fieldMessage(t: ReturnType<typeof useI18n>["t"], code: string): string {
  const key = `error.field.${code}` as MessageKey;
  return t(["required", "too_short", "too_long", "unknown_member"].includes(code) ? key : "error.field.invalid");
}

/** The form of the CRUD generator: one input per writable field of the metadata, plus the mandatory reason. */
export function EntityForm({ mode, slug, id, version: initialVersion, fields, initial, listHref }: Props) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [values, setValues] = useState<Record<string, string>>(initial);
  const [reason, setReason] = useState("");
  const [version, setVersion] = useState(initialVersion);
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setErrors({});
    setBanner(null);
    const sent: Record<string, unknown> = {};
    for (const f of fields) {
      const v = values[f.name] ?? "";
      if (mode === "update" && v === (initial[f.name] ?? "")) continue;
      if (mode === "create" && v === "" && !f.required) continue;
      sent[f.name] = v;
    }
    const local: Record<string, string> = {};
    if (reason.trim().length < REASON_MIN_LENGTH) local.reason = t("admin.reason.too_short");
    if (mode === "update" && Object.keys(sent).length === 0) {
      setBanner({ kind: "error", text: t("admin.no_changes") });
      if (local.reason) setErrors(local);
      return;
    }
    if (Object.keys(local).length) {
      setErrors(local);
      return;
    }
    const body: WriteRequest = { values: sent, reason: reason.trim(), ...(mode === "update" ? { version } : {}) };
    setBusy(true);
    try {
      const res = await fetch(mode === "create" ? `/api/bff/admin/${slug}` : `/api/bff/admin/${slug}/${id}`, {
        method: mode === "create" ? "POST" : "PATCH",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify(body),
      });
      const data = (await res.json().catch(() => ({}))) as { row?: { version?: number } } & Partial<Problem>;
      if (res.ok) {
        if (mode === "create") {
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

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4 rounded-lg border border-slate-200 bg-white p-4 shadow-sm" data-testid="entity-form">
      {fields.map((f) => (
        <Field key={f.name} label={f.label} htmlFor={`f-${f.name}`} required={f.required} error={errors[f.name]}>
          {f.kind === "enum" ? (
            <select id={`f-${f.name}`} name={f.name} value={values[f.name] ?? ""} onChange={(e) => setValues({ ...values, [f.name]: e.target.value })} className={inputClass}>
              {f.nullable || !f.required ? <option value="">{t("common.none")}</option> : null}
              {f.options?.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          ) : (
            <input
              id={`f-${f.name}`}
              name={f.name}
              value={values[f.name] ?? ""}
              onChange={(e) => setValues({ ...values, [f.name]: e.target.value })}
              inputMode={f.kind === "int" ? "numeric" : undefined}
              maxLength={f.maxLength}
              aria-invalid={errors[f.name] ? true : undefined}
              className={inputClass}
            />
          )}
        </Field>
      ))}
      <ReasonField value={reason} onChange={setReason} error={errors.reason} />
      {banner ? (
        <p role={banner.kind === "error" ? "alert" : "status"} data-testid={banner.kind === "ok" ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.kind === "ok" ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>
          {banner.text}
        </p>
      ) : null}
      <div className="flex gap-3">
        <button type="submit" disabled={busy} className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
          {t(mode === "create" ? "common.create" : "common.save")}
        </button>
        <a href={listHref} className="rounded border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-100">
          {t("common.cancel")}
        </a>
      </div>
    </form>
  );
}
