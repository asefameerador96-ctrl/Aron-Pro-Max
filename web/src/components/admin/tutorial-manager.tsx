"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { ALL_ROLES } from "@/lib/auth/roles";
import { KIND_MIME, KIND_PURPOSE, MAX_ASSET_BYTES, TUTORIAL_KINDS, type TutorialKind } from "@/lib/admin/tutorials";
import type { MessageKey } from "@/lib/i18n";
import { Field, inputClass } from "./kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "./kit/reason-field";

export interface TutorialRow {
  tutorial_id: number;
  kind: TutorialKind;
  title_en: string;
  title_bn: string | null;
  sort: number;
  roles: string[];
  status: "active" | "inactive";
  version: number;
  bytes: number | null;
  /** Not in the contract's TutorialAdmin yet (docs/requests/web-admin-tutorial-asset-id.md): without it an edit needs a new file. */
  asset_id?: string;
}

const hex = (buf: ArrayBuffer) => Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, "0")).join("");

/** Tutorials list with add and edit. The file goes straight to the write-only upload URL; only metadata passes through the BFF. */
export function TutorialManager({ items, canWrite }: { items: TutorialRow[]; canWrite: boolean }) {
  const { t, number, problem } = useI18n();
  const router = useRouter();
  const [editing, setEditing] = useState<TutorialRow | null>(null);
  const [kind, setKind] = useState<TutorialKind>("video");
  const [titleEn, setTitleEn] = useState("");
  const [titleBn, setTitleBn] = useState("");
  const [roles, setRoles] = useState<string[]>([]);
  const [sort, setSort] = useState("0");
  const [status, setStatus] = useState<"active" | "inactive">("active");
  const [file, setFile] = useState<File | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  function startEdit(r: TutorialRow | null) {
    setEditing(r);
    setKind(r?.kind ?? "video");
    setTitleEn(r?.title_en ?? "");
    setTitleBn(r?.title_bn ?? "");
    setRoles(r?.roles ?? []);
    setSort(String(r?.sort ?? 0));
    setStatus(r?.status ?? "active");
    setFile(null);
    setReason("");
    setErrors({});
    setMessage(null);
  }

  async function submit() {
    const e: Record<string, string> = {};
    const n = Array.from(reason.trim()).length;
    if (!titleEn.trim()) e.title_en = t("error.field.required");
    else if (Array.from(titleEn.trim()).length > 120) e.title_en = t("error.field.too_long");
    if (Array.from(titleBn.trim()).length > 120) e.title_bn = t("error.field.too_long");
    if (roles.length === 0) e.roles = t("error.field.required");
    if (!/^[0-9]{1,4}$/.test(sort.trim())) e.sort = t("error.field.invalid");
    if (file && file.type !== KIND_MIME[kind]) e.file = t("tut.file_type");
    else if (file && file.size > MAX_ASSET_BYTES) e.file = t("tut.file_big");
    else if (file && file.size < 1) e.file = t("error.field.invalid");
    else if (!file && !editing?.asset_id) e.file = t("tut.file_required");
    if (n < REASON_MIN_LENGTH) e.reason = t("admin.reason.too_short");
    else if (n > 500) e.reason = t("error.field.too_long");
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    setMessage(null);
    try {
      let assetId = editing?.asset_id ?? "";
      if (file) {
        setMessage({ ok: true, text: t("tut.uploading") });
        assetId = crypto.randomUUID();
        const sha256 = hex(await crypto.subtle.digest("SHA-256", await file.arrayBuffer()));
        const ticket = await fetch("/api/bff/admin/assets", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ asset_id: assetId, purpose: KIND_PURPOSE[kind], mime: file.type, bytes: file.size, sha256 }) });
        const t1 = (await ticket.json().catch(() => ({}))) as { data?: { upload_url?: string }; code?: string };
        if (!ticket.ok || !t1.data?.upload_url) return setMessage({ ok: false, text: problem((t1.code ?? "ERR_INTERNAL") as never) });
        const put = await fetch(t1.data.upload_url, { method: "PUT", headers: { "x-ms-blob-type": "BlockBlob", "Content-Type": file.type }, body: file });
        if (!put.ok) return setMessage({ ok: false, text: t("tut.upload_failed") });
      }
      const body = { kind, title_en: titleEn.trim(), title_bn: titleBn.trim() || null, asset_id: assetId, roles, sort: Number(sort), status, change_reason: reason.trim(), ...(editing ? { version: editing.version } : {}) };
      const res = await fetch(editing ? `/api/bff/admin/tutorials/${editing.tutorial_id}` : "/api/bff/admin/tutorials", { method: editing ? "PATCH" : "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify(body) });
      const json = (await res.json().catch(() => ({}))) as { code?: string };
      if (!res.ok) return setMessage({ ok: false, text: problem((json.code ?? "ERR_INTERNAL") as never) });
      startEdit(null);
      setMessage({ ok: true, text: t("tut.saved") });
      router.refresh();
    } catch {
      setMessage({ ok: false, text: t("tut.upload_failed") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-6" data-testid="tutorials">
      {items.length === 0 ? (
        <p className="text-slate-600">{t("common.empty")}</p>
      ) : (
        <table className="w-full text-left text-sm" data-testid="data-table" aria-label={t("menu.admin.tutorials")}>
          <thead>
            <tr className="border-b border-slate-200 text-slate-600">
              <th className="p-2">{t("tut.title_en")}</th>
              <th className="p-2">{t("tut.kind")}</th>
              <th className="p-2">{t("tut.roles")}</th>
              <th className="p-2">{t("entity.field.sort")}</th>
              <th className="p-2">{t("entity.field.status")}</th>
              {canWrite ? <th className="p-2" /> : null}
            </tr>
          </thead>
          <tbody>
            {items.map((r) => (
              <tr key={r.tutorial_id} className="border-b border-slate-100">
                <td className="p-2 break-words">{r.title_en}{r.title_bn ? ` · ${r.title_bn}` : ""}</td>
                <td className="p-2">{t(`tut.kind.${r.kind}` as MessageKey)}</td>
                <td className="p-2">{r.roles.map((x) => t(`role.${x}` as MessageKey)).join(", ")}</td>
                <td className="p-2">{number(r.sort)}</td>
                <td className="p-2">{t(r.status === "active" ? "entity.status.active" : "entity.status.disabled")}</td>
                {canWrite ? (
                  <td className="p-2">
                    <button type="button" data-testid={`edit-${r.tutorial_id}`} onClick={() => startEdit(r)} aria-label={`${t("common.edit")}: ${r.title_en}`} className="text-brand-600 underline">
                      {t("common.edit")}
                    </button>
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {canWrite ? (
        <section className="space-y-4 rounded-lg border border-slate-200 bg-white p-4" aria-labelledby="tut-form-title">
          <h2 id="tut-form-title" className="font-semibold">
            {editing ? t("common.edit") : t("tut.add")}
          </h2>
          <Field label={t("tut.kind")} htmlFor="f-kind" required>
            <select id="f-kind" value={kind} onChange={(ev) => setKind(ev.target.value as TutorialKind)} className={`${inputClass} w-64`}>
              {TUTORIAL_KINDS.map((k) => (
                <option key={k} value={k}>
                  {t(`tut.kind.${k}` as MessageKey)}
                </option>
              ))}
            </select>
          </Field>
          <Field label={t("tut.title_en")} htmlFor="f-title_en" required error={errors.title_en}>
            <input id="f-title_en" aria-required="true" maxLength={120} value={titleEn} onChange={(ev) => setTitleEn(ev.target.value)} className={inputClass} />
          </Field>
          <Field label={t("tut.title_bn")} htmlFor="f-title_bn" error={errors.title_bn}>
            <input id="f-title_bn" maxLength={120} value={titleBn} onChange={(ev) => setTitleBn(ev.target.value)} className={inputClass} />
          </Field>
          <fieldset aria-describedby={errors.roles ? "err-roles" : undefined}>
            <legend className="text-sm font-medium text-slate-800">
              {t("tut.roles")}
              <span aria-hidden="true" className="ml-1 text-red-600">*</span>
            </legend>
            <div className="mt-1 flex flex-wrap gap-3">
              {ALL_ROLES.map((r) => (
                <label key={r} className="flex items-center gap-1 text-sm">
                  <input type="checkbox" data-testid={`role-${r}`} checked={roles.includes(r)} onChange={(ev) => setRoles(ev.target.checked ? [...roles, r] : roles.filter((x) => x !== r))} />
                  {t(`role.${r}` as MessageKey)}
                </label>
              ))}
            </div>
            {errors.roles ? <p id="err-roles" role="alert" className="text-xs font-medium text-red-700">{errors.roles}</p> : null}
          </fieldset>
          <Field label={t("entity.field.sort")} htmlFor="f-sort" required error={errors.sort}>
            <input id="f-sort" inputMode="numeric" aria-required="true" value={sort} onChange={(ev) => setSort(ev.target.value)} className={`${inputClass} w-32`} />
          </Field>
          {editing ? (
            <Field label={t("entity.field.status")} htmlFor="f-status">
              <select id="f-status" value={status} onChange={(ev) => setStatus(ev.target.value as "active" | "inactive")} className={`${inputClass} w-48`}>
                <option value="active">{t("entity.status.active")}</option>
                <option value="inactive">{t("entity.status.disabled")}</option>
              </select>
            </Field>
          ) : null}
          <Field label={t("tut.file")} htmlFor="f-file" required={!editing?.asset_id} hint={editing && !editing.asset_id ? t("tut.replace_note") : t("tut.file_hint")} error={errors.file}>
            <input id="f-file" type="file" accept={KIND_MIME[kind]} onChange={(ev) => setFile(ev.target.files?.[0] ?? null)} className={inputClass} />
          </Field>
          <ReasonField value={reason} onChange={setReason} error={errors.reason} />
          <div className="flex gap-2">
            <button type="button" disabled={busy} onClick={submit} data-testid="tutorial-submit" className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
              {t("common.save")}
            </button>
            {editing ? (
              <button type="button" disabled={busy} onClick={() => startEdit(null)} className="rounded border border-slate-300 px-4 py-2 text-sm">
                {t("common.cancel")}
              </button>
            ) : null}
          </div>
          {message ? (
            <p role={message.ok ? "status" : "alert"} data-testid="tutorial-message" className={`rounded p-2 text-sm ${message.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>
              {message.text}
            </p>
          ) : null}
        </section>
      ) : null}
    </div>
  );
}
