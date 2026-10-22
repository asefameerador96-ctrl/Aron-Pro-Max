"use client";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { MessageKey } from "@/lib/i18n";
import { en } from "@/lib/i18n/messages-en";
import type { Problem } from "@/contract/types";
import type { AssetPurpose } from "@/lib/admin/tutorials";
import { fileProblem, uploadAsset } from "../asset-upload";
import { Field, inputClass } from "../kit/field";
import { packPreview } from "@/lib/admin/sku-pack";
import { ReasonField, REASON_MIN_LENGTH } from "../kit/reason-field";
import type { FieldError, WriteRequest } from "./types";

export interface FormFieldDef {
  name: string;
  label: string;
  kind: "text" | "int" | "enum" | "date" | "mask" | "number" | "asset";
  /** Translated section heading; consecutive fields with the same heading are grouped. */
  section?: string;
  /** Labels of the bits of a `mask` field, bit 0 first. */
  maskBits?: string[];
  required: boolean;
  /** Required enum with no preselected option: the user must choose. */
  noDefault?: boolean;
  nullable: boolean;
  maxLength?: number;
  options?: { value: string; label: string }[];
  /** `asset`: purpose of the upload ticket, and the URL of the file now stored (shown as a preview). */
  assetPurpose?: AssetPurpose;
  previewUrl?: string;
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
  /** A live preview under the fields. `base` holds the stored values of members that are not in the form (create-only on an edit page). */
  preview?: { kind: "sku-pack"; base: Record<string, string> };
}

/** Value of an asset field the user removed: sent as "" (null) even when the stored value is not in the row. */
const CLEAR = "-";

function fieldMessage(t: ReturnType<typeof useI18n>["t"], code: string): string {
  const key = `error.field.${code}` as MessageKey;
  return t(["required", "too_short", "too_long", "unknown_member", "past"].includes(code) ? key : "error.field.invalid");
}

/** The form of the CRUD generator: one input per writable field of the metadata, plus the mandatory reason. */
export function EntityForm({ mode, slug, action, submitLabel, reasonStored = true, id, version: initialVersion, fields, initial, listHref, preview }: Props) {
  const { t, problem, dateTime } = useI18n();
  const router = useRouter();
  const [values, setValues] = useState<Record<string, string>>(initial);
  const [reason, setReason] = useState("");
  const [version, setVersion] = useState(initialVersion);
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [shown, setShown] = useState<Record<string, unknown> | null>(null);
  const [uploading, setUploading] = useState<string | null>(null);
  const [picked, setPicked] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  async function onPick(f: FormFieldDef, file: File | undefined) {
    if (!file || !f.assetPurpose) return;
    const bad = fileProblem(file, f.assetPurpose);
    if (bad) {
      setErrors((x) => ({ ...x, [f.name]: t(bad === "type" ? "asset.bad_type" : bad === "big" ? "asset.too_big" : "asset.empty") }));
      return;
    }
    setErrors((x) => ({ ...x, [f.name]: "" }));
    setUploading(f.name);
    try {
      const r = await uploadAsset(file, f.assetPurpose);
      if (r.ok) {
        setValues((v) => ({ ...v, [f.name]: r.assetId }));
        setPicked((p) => ({ ...p, [f.name]: file.name }));
      } else {
        setErrors((x) => ({ ...x, [f.name]: r.code === "upload_failed" ? t("tut.upload_failed") : problem(r.code) }));
      }
    } catch {
      setErrors((x) => ({ ...x, [f.name]: t("tut.upload_failed") }));
    } finally {
      setUploading(null);
    }
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setErrors({});
    setBanner(null);
    const sent: Record<string, unknown> = {};
    for (const f of fields) {
      const v = values[f.name] ?? "";
      if (f.kind === "asset" && v === CLEAR) {
        sent[f.name] = "";
        continue;
      }
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
        // A saved file is the stored one now: clear the "ready" / "removed" state so the next save does not re-send it.
        setValues((x) => Object.fromEntries(Object.entries(x).map(([k, val]) => [k, fields.find((f) => f.name === k)?.kind === "asset" ? "" : val])));
        setPicked({});
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
              {/* A required choice with no value yet shows an unselectable prompt: without it the browser displays the first option while the value is still empty. */}
              {!f.nullable && f.required && (values[f.name] ?? "") === "" ? <option value="" disabled>{t("common.choose")}</option> : null}
              {f.options?.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          ) : f.kind === "asset" ? (
            <div className="space-y-2" data-testid={`asset-${f.name}`}>
              {values[f.name] === CLEAR ? (
                <p className="text-sm text-amber-800">{t("asset.removed")}</p>
              ) : values[f.name] && picked[f.name] ? (
                <p className="text-sm text-green-800">{t("asset.ready", { name: picked[f.name] ?? "" })}</p>
              ) : f.previewUrl ? (
                // eslint-disable-next-line @next/next/no-img-element -- a 300 KB admin thumbnail from the storage origin; next/image would proxy it
                <img src={f.previewUrl} alt={t("asset.current")} width={96} height={96} className="h-24 w-24 rounded border border-slate-200 object-contain" />
              ) : (
                <p className="text-sm text-slate-600">{t("asset.none")}</p>
              )}
              <input
                type="file"
                id={`f-${f.name}`}
                name={`${f.name}-file`}
                accept="image/jpeg,image/png"
                disabled={uploading !== null}
                onChange={(e) => {
                  void onPick(f, e.target.files?.[0]);
                  e.target.value = "";
                }}
                className="block text-sm"
              />
              {uploading === f.name ? <p role="status" className="text-sm">{t("tut.uploading")}</p> : null}
              {(values[f.name] && values[f.name] !== CLEAR) || (mode === "update" && f.previewUrl && values[f.name] !== CLEAR) ? (
                <button type="button" onClick={() => { setValues({ ...values, [f.name]: mode === "update" ? CLEAR : "" }); setPicked({ ...picked, [f.name]: "" }); }} className="rounded border border-slate-300 px-3 py-1 text-sm hover:bg-slate-100">
                  {t("asset.clear")}
                </button>
              ) : null}
              <p className="text-xs text-slate-600">{t("asset.hint_image")}</p>
            </div>
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
      {preview?.kind === "sku-pack" ? <SkuPackPreview values={{ ...preview.base, ...values }} editing={mode === "update"} /> : null}
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
        <button type="submit" disabled={busy || uploading !== null} className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
          {mode === "action" && submitLabel ? submitLabel : t(mode === "create" ? "common.create" : "common.save")}
        </button>
        <a href={listHref} className="rounded border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-100">
          {t("common.cancel")}
        </a>
      </div>
    </form>
  );
}

/** "1 packet = 20 sticks": what the field apps will show for the pack size being entered (docs/34 s4.2), with non-blocking warnings. */
function SkuPackPreview({ values, editing }: { values: Record<string, string>; editing: boolean }) {
  const { t } = useI18n();
  const p = packPreview({ category: values.category_code ?? "", baseUnit: values.base_unit ?? "", basePerPack: values.base_per_pack ?? "", entryUnit: values.entry_unit_default ?? "" });
  return (
    <section data-testid="sku-pack" aria-live="polite" className="space-y-1 rounded-lg border border-slate-200 bg-slate-50 p-3">
      <h2 className="text-sm font-semibold text-slate-700">{t("skupack.preview.title")}</h2>
      {p.sentence ? <p data-testid="sku-pack-preview" className="text-base font-medium">{t(p.sentence.key, p.sentence.vars)}</p> : <p data-testid="sku-pack-pending" className="text-sm text-slate-600">{t("skupack.preview.pending")}</p>}
      {p.label ? <p className="text-sm text-slate-700">{t("skupack.label")}: <strong data-testid="sku-pack-label">{t(`skupack.label.${p.label}` as "skupack.label.packet")}</strong></p> : null}
      {p.warnings.map((w) => (<p key={w} role="note" data-testid="sku-pack-warning" className="text-sm text-amber-800">{t(w)}</p>))}
      {editing ? <p className="text-xs text-slate-600">{t("skupack.edit_note")}</p> : null}
    </section>
  );
}
