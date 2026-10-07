"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { CONTENT_KINDS, DEF_MAX_ITEMS, RUBRIC_ANSWERS, RUBRIC_KINDS, SCOPE_TYPES, SURVEY_ANSWERS, SURVEY_KINDS } from "@/lib/admin/definitions";
import type { MessageKey } from "@/lib/i18n";
import { asciiDigits } from "./crud/validation";
import { fileProblem, uploadAsset } from "./asset-upload";
import { Field, inputClass } from "./kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "./kit/reason-field";

export type DefKind = "surveys" | "rubrics" | "content";
type Row = Record<string, unknown> & { version: number };
interface Item {
  key: string;
  label_en: string;
  label_bn: string;
  answer_type: string;
  required: boolean;
  photo: boolean;
  show_if_key: string;
  show_if_bool: string;
}
interface Scope {
  node_type: string;
  node_id: string;
}

const ID_KEY = { surveys: "survey_id", rubrics: "rubric_id", content: "content_id" } as const;
const KINDS = { surveys: SURVEY_KINDS, rubrics: RUBRIC_KINDS, content: CONTENT_KINDS } as const;
const blankItem = (n: number): Item => ({ key: `q${n}`, label_en: "", label_bn: "", answer_type: "bool", required: false, photo: false, show_if_key: "", show_if_bool: "" });
// REQUEST: web-admin-definition-reads. The read has no keys and a different rubric answer enum, so a template regenerates both.
const RUBRIC_READ_TO_WRITE: Record<string, string> = { score_1_5: "stars_1_5", bool: "bool", text: "text" };

/** List and editor of the survey, rubric and content definitions. Editing publishes a new version (PATCH with If-Match). */
export function DefinitionManager({ kind, items, canWrite }: { kind: DefKind; items: Row[]; canWrite: boolean }) {
  const { t, number, problem } = useI18n();
  const router = useRouter();
  const [editing, setEditing] = useState<Row | null>(null);
  const [open, setOpen] = useState(false);
  const [defKind, setDefKind] = useState<string>(KINDS[kind][0]);
  const [titleEn, setTitleEn] = useState("");
  const [titleBn, setTitleBn] = useState("");
  const [validFrom, setValidFrom] = useState("");
  const [validTo, setValidTo] = useState("");
  const [status, setStatus] = useState<"active" | "inactive">("active");
  const [sequence, setSequence] = useState("1");
  const [rows, setRows] = useState<Item[]>([blankItem(1)]);
  const [scope, setScope] = useState<Scope[]>([]);
  const [file, setFile] = useState<File | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  function start(r: Row | null) {
    setOpen(true);
    setEditing(r);
    setErrors({});
    setMessage(null);
    setReason("");
    setFile(null);
    setDefKind(String(r?.kind ?? KINDS[kind][0]));
    setTitleEn(String(r?.title_en ?? ""));
    setTitleBn(String(r?.title_bn ?? ""));
    setValidFrom(String(r?.valid_from ?? ""));
    setValidTo(String(r?.valid_to ?? ""));
    setStatus(r?.status === "inactive" ? "inactive" : "active");
    setSequence(String(r?.sequence ?? 1));
    setScope(((r?.assigned_scope as { node_type: string; node_id: number }[] | undefined) ?? []).map((s) => ({ node_type: s.node_type, node_id: String(s.node_id) })));
    const src = (((r?.questions ?? r?.criteria) as Record<string, unknown>[] | undefined) ?? []).filter((q) => q.enabled !== false); // a disabled placeholder must not go live (the write has no `enabled`)
    setRows(
      src.length
        ? src.map((q, i) => ({ ...blankItem(i + 1), label_en: String(q.label_en ?? ""), label_bn: String(q.label_bn ?? ""), answer_type: kind === "rubrics" ? (RUBRIC_READ_TO_WRITE[String(q.answer_type)] ?? "text") : String(q.answer_type ?? "bool"), photo: q.requires_photo === true }))
        : [blankItem(1)],
    );
  }
  const setRow = (i: number, patch: Partial<Item>) => setRows(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));

  async function submit() {
    const e: Record<string, string> = {};
    const n = Array.from(reason.trim()).length;
    if (kind !== "rubrics" && !titleEn.trim()) e.title_en = t("error.field.required");
    if (kind !== "rubrics" && Array.from(titleEn.trim()).length > 120) e.title_en = t("error.field.too_long");
    if (kind !== "rubrics" && !/^\d{4}-\d{2}-\d{2}$/.test(validFrom)) e.valid_from = t("error.field.required");
    if (kind === "content" && !/^\d{4}-\d{2}-\d{2}$/.test(validTo)) e.valid_to = t("error.field.required");
    if (validFrom && validTo && validTo < validFrom) e.valid_to = t("error.field.invalid");
    const seq = String(asciiDigits(sequence)).trim();
    if (kind === "content" && (!/^[0-9]{1,2}$/.test(seq) || Number(seq) < 1 || Number(seq) > 20)) e.sequence = t("error.field.invalid");
    if (kind !== "content") {
      if (rows.length < 1 || rows.length > DEF_MAX_ITEMS) e.rows = t("error.field.invalid");
      else if (rows.some((r) => !r.label_en.trim() || !/^[a-z][a-z0-9_]{1,40}$/.test(r.key))) e.rows = t("def.rows_invalid");
      else if (new Set(rows.map((r) => r.key)).size !== rows.length) e.rows = t("def.keys_unique");
      else if (kind === "surveys" && rows.some((r, i) => r.show_if_key && !rows.slice(0, i).some((p) => p.key === r.show_if_key))) e.rows = t("def.stale_condition");
    }
    const purpose = defKind === "kv" ? "content_kv" : "content_av";
    if (kind === "content") {
      if (file) {
        const p = fileProblem(file, purpose);
        if (p) e.file = t(p === "type" ? "tut.file_type" : p === "big" ? (defKind === "kv" ? "def.file_big_kv" : "def.file_big_av") : "error.field.invalid");
      } else e.file = t("tut.file_required"); // REQUEST: web-admin-definition-reads (asset_id is not readable)
      if (scope.some((s) => !/^[0-9]{1,15}$/.test(String(asciiDigits(s.node_id)).trim()) || Number(String(asciiDigits(s.node_id)).trim()) < 1)) e.scope = t("error.field.invalid");
    }
    if (n < REASON_MIN_LENGTH) e.reason = t("admin.reason.too_short");
    else if (n > 500) e.reason = t("error.field.too_long");
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    setMessage(null);
    try {
      const body: Record<string, unknown> = { kind: defKind, change_reason: reason.trim() };
      if (kind !== "rubrics") {
        body.title_en = titleEn.trim();
        body.title_bn = titleBn.trim() || null;
        body.valid_from = validFrom;
        body.valid_to = validTo || null;
      }
      if (kind === "surveys") body.questions = rows.map((r) => ({ key: r.key, answer_type: r.answer_type, label_en: r.label_en.trim(), label_bn: r.label_bn.trim() || null, required: r.required, photo: r.photo, ...(r.show_if_key ? { show_if_key: r.show_if_key, show_if_bool: r.show_if_bool === "" ? null : r.show_if_bool === "true" } : {}) }));
      if (kind === "rubrics") body.criteria = rows.map((r) => ({ key: r.key, answer_type: r.answer_type, label_en: r.label_en.trim(), label_bn: r.label_bn.trim() || null }));
      if (kind === "content") {
        setMessage({ ok: true, text: t("tut.uploading") });
        const up = await uploadAsset(file!, purpose);
        if (!up.ok) return setMessage({ ok: false, text: up.code === "upload_failed" ? t("tut.upload_failed") : problem(up.code as never) });
        body.asset_id = up.assetId;
        body.sequence = Number(seq);
        body.assigned_scope = scope.map((s) => ({ node_type: s.node_type, node_id: Number(String(asciiDigits(s.node_id)).trim()) }));
      }
      if (editing) {
        body.version = editing.version;
        body.status = status;
      }
      const res = await fetch(editing ? `/api/bff/admin/defs/${kind}/${editing[ID_KEY[kind]]}` : `/api/bff/admin/defs/${kind}`, { method: editing ? "PATCH" : "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify(body) });
      const json = (await res.json().catch(() => ({}))) as { code?: string };
      if (!res.ok) return setMessage({ ok: false, text: problem((json.code ?? "ERR_INTERNAL") as never) });
      setOpen(false);
      setEditing(null);
      setMessage({ ok: true, text: t("tut.saved") });
      router.refresh();
    } catch {
      setMessage({ ok: false, text: t("tut.upload_failed") });
    } finally {
      setBusy(false);
    }
  }

  const answers = kind === "surveys" ? SURVEY_ANSWERS : RUBRIC_ANSWERS;
  return (
    <div className="space-y-6" data-testid="definitions">
      {items.length === 0 ? (
        <p className="text-slate-600">{t("common.empty")}</p>
      ) : (
        <table className="w-full text-left text-sm" data-testid="data-table" aria-label={t(`def.${kind}.title` as MessageKey)}>
          <thead>
            <tr className="border-b border-slate-200 text-slate-600">
              <th className="p-2">{t("def.name")}</th>
              <th className="p-2">{t("tut.kind")}</th>
              {kind !== "rubrics" ? <th className="p-2">{t("def.validity")}</th> : null}
              {kind === "content" ? <th className="p-2">{t("def.scope")}</th> : null}
              <th className="p-2">{t("def.version")}</th>
              <th className="p-2">{t("entity.field.status")}</th>
              {canWrite ? <th className="p-2" /> : null}
            </tr>
          </thead>
          <tbody>
            {items.map((r) => {
              const id = Number(r[ID_KEY[kind]]);
              const kindLabel = t(`def.kind.${String(r.kind)}` as MessageKey);
              const name = r.title_en ? String(r.title_en) : kindLabel;
              return (
                <tr key={id} className="border-b border-slate-100">
                  <td className="p-2 break-words">{name}{r.title_bn ? ` · ${String(r.title_bn)}` : ""}</td>
                  <td className="p-2">{kindLabel}</td>
                  {kind !== "rubrics" ? <td className="p-2">{`${String(r.valid_from ?? "")} – ${String(r.valid_to ?? "")}`}</td> : null}
                  {kind === "content" ? <td className="p-2">{((r.assigned_scope as { node_type: string; node_id: number }[] | undefined) ?? []).map((n) => `${t(`def.node.${n.node_type}` as MessageKey)} ${n.node_id}`).join(", ") || t("def.all_outlets")}</td> : null}
                  <td className="p-2">{number(r.version)}</td>
                  <td className="p-2">{t(r.status === "active" ? "entity.status.active" : "entity.status.disabled")}</td>
                  {canWrite ? (
                    <td className="p-2">
                      <button type="button" data-testid={`edit-${id}`} onClick={() => start(r)} aria-label={`${t("def.new_version")}: ${name}`} className="text-brand-600 underline">
                        {t("def.new_version")}
                      </button>
                    </td>
                  ) : null}
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {canWrite && !open ? (
        <button type="button" data-testid="def-new" onClick={() => start(null)} className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700">
          {t("def.new")}
        </button>
      ) : null}
      {canWrite && open ? (
        <section className="space-y-4 rounded-lg border border-slate-200 bg-white p-4" aria-labelledby="def-form-title">
          <h2 id="def-form-title" className="font-semibold">{editing ? t("def.new_version") : t("def.new")}</h2>
          {editing ? <p className="text-sm text-slate-600">{t("def.template_note")}</p> : null}
          <Field label={t("tut.kind")} htmlFor="f-kind" required>
            <select id="f-kind" value={defKind} onChange={(ev) => setDefKind(ev.target.value)} className={`${inputClass} w-64`}>
              {KINDS[kind].map((k) => (
                <option key={k} value={k}>{t(`def.kind.${k}` as MessageKey)}</option>
              ))}
            </select>
          </Field>
          {editing ? (
            <Field label={t("entity.field.status")} htmlFor="f-status">
              <select id="f-status" aria-label={t("entity.field.status")} value={status} onChange={(ev) => setStatus(ev.target.value as "active" | "inactive")} className={`${inputClass} w-48`}>
                <option value="active">{t("entity.status.active")}</option>
                <option value="inactive">{t("entity.status.disabled")}</option>
              </select>
            </Field>
          ) : null}
          {kind !== "rubrics" ? (
            <>
              <Field label={t("tut.title_en")} htmlFor="f-title_en" required error={errors.title_en}>
                <input id="f-title_en" aria-required="true" maxLength={120} value={titleEn} onChange={(ev) => setTitleEn(ev.target.value)} className={inputClass} />
              </Field>
              <Field label={t("tut.title_bn")} htmlFor="f-title_bn">
                <input id="f-title_bn" maxLength={120} value={titleBn} onChange={(ev) => setTitleBn(ev.target.value)} className={inputClass} />
              </Field>
              <Field label={t("def.valid_from")} htmlFor="f-valid_from" required error={errors.valid_from}>
                <input id="f-valid_from" type="date" aria-required="true" value={validFrom} onChange={(ev) => setValidFrom(ev.target.value)} className={`${inputClass} w-48`} />
              </Field>
              <Field label={t("def.valid_to")} htmlFor="f-valid_to" required={kind === "content"} error={errors.valid_to}>
                <input id="f-valid_to" type="date" value={validTo} onChange={(ev) => setValidTo(ev.target.value)} className={`${inputClass} w-48`} />
              </Field>
            </>
          ) : null}
          {kind !== "content" ? (
            <fieldset className="space-y-3">
              <legend className="text-sm font-medium text-slate-800">{t(kind === "surveys" ? "def.questions" : "def.criteria")}</legend>
              {rows.map((r, i) => (
                <div key={i} className="grid gap-2 rounded border border-slate-200 p-3 sm:grid-cols-2" data-testid={`row-${i}`}>
                  <input aria-label={`${t("def.key")} ${i + 1}`} data-testid={`key-${i}`} value={r.key} onChange={(ev) => setRow(i, { key: ev.target.value })} className={inputClass} />
                  <select aria-label={t("def.answer_type")} value={r.answer_type} onChange={(ev) => setRow(i, { answer_type: ev.target.value })} className={inputClass}>
                    {answers.map((a) => (
                      <option key={a} value={a}>{t(`def.answer.${a}` as MessageKey)}</option>
                    ))}
                  </select>
                  <input aria-label={`${t("def.label_en")} ${i + 1}`} data-testid={`label-${i}`} maxLength={300} value={r.label_en} onChange={(ev) => setRow(i, { label_en: ev.target.value })} className={inputClass} />
                  <input aria-label={`${t("def.label_bn")} ${i + 1}`} maxLength={300} value={r.label_bn} onChange={(ev) => setRow(i, { label_bn: ev.target.value })} className={inputClass} />
                  {kind === "surveys" ? (
                    <div className="flex flex-wrap items-center gap-3 text-sm sm:col-span-2">
                      <label className="flex items-center gap-1"><input type="checkbox" checked={r.required} onChange={(ev) => setRow(i, { required: ev.target.checked })} />{t("def.required")}</label>
                      <label className="flex items-center gap-1"><input type="checkbox" checked={r.photo} onChange={(ev) => setRow(i, { photo: ev.target.checked })} />{t("def.photo")}</label>
                      <select aria-label={t("def.show_if")} value={r.show_if_key} onChange={(ev) => setRow(i, { show_if_key: ev.target.value, show_if_bool: ev.target.value ? r.show_if_bool || "true" : "" })} className={`${inputClass} w-48`}>
                        <option value="">{t("def.always")}</option>
                        {rows.slice(0, i).map((p) => (
                          <option key={p.key} value={p.key}>{p.key}</option>
                        ))}
                      </select>
                      {r.show_if_key ? (
                        <select aria-label={t("def.show_if_value")} value={r.show_if_bool} onChange={(ev) => setRow(i, { show_if_bool: ev.target.value })} className={`${inputClass} w-32`}>
                          <option value="true">{t("def.yes")}</option>
                          <option value="false">{t("def.no")}</option>
                        </select>
                      ) : null}
                    </div>
                  ) : null}
                  <button type="button" onClick={() => setRows(rows.filter((_, j) => j !== i))} disabled={rows.length <= 1} aria-label={`${t("def.remove")} ${i + 1}`} className="justify-self-start text-sm text-red-700 underline disabled:opacity-40">
                    {t("def.remove")}
                  </button>
                </div>
              ))}
              {errors.rows ? <p role="alert" data-testid="error-rows" className="text-xs font-medium text-red-700">{errors.rows}</p> : null}
              <button type="button" data-testid="def-add-row" disabled={rows.length >= DEF_MAX_ITEMS} onClick={() => setRows([...rows, blankItem(rows.length + 1)])} className="rounded border border-slate-300 px-3 py-1 text-sm disabled:opacity-40">
                {t("def.add_row")}
              </button>
            </fieldset>
          ) : (
            <>
              <Field label={t("def.sequence")} htmlFor="f-sequence" required error={errors.sequence}>
                <input id="f-sequence" inputMode="numeric" value={sequence} onChange={(ev) => setSequence(ev.target.value)} className={`${inputClass} w-24`} />
              </Field>
              <Field label={t("tut.file")} htmlFor="f-file" required error={errors.file} hint={t("def.content_hint")}>
                <input id="f-file" type="file" accept={defKind === "kv" ? "image/jpeg,image/png" : "video/mp4"} onChange={(ev) => setFile(ev.target.files?.[0] ?? null)} className={inputClass} />
              </Field>
              <fieldset className="space-y-2">
                <legend className="text-sm font-medium text-slate-800">{t("def.scope")}</legend>
                <p className="text-xs text-slate-500">{t("def.scope_hint")}</p>
                {scope.map((s, i) => (
                  <div key={i} className="flex gap-2">
                    <select aria-label={t("def.node_type")} value={s.node_type} onChange={(ev) => setScope(scope.map((x, j) => (j === i ? { ...x, node_type: ev.target.value } : x)))} className={`${inputClass} w-40`}>
                      {SCOPE_TYPES.map((n) => (
                        <option key={n} value={n}>{t(`def.node.${n}` as MessageKey)}</option>
                      ))}
                    </select>
                    <input aria-label={t("def.node_id")} inputMode="numeric" value={s.node_id} onChange={(ev) => setScope(scope.map((x, j) => (j === i ? { ...x, node_id: ev.target.value } : x)))} className={`${inputClass} w-32`} />
                    <button type="button" onClick={() => setScope(scope.filter((_, j) => j !== i))} className="text-sm text-red-700 underline">{t("def.remove")}</button>
                  </div>
                ))}
                {errors.scope ? <p role="alert" className="text-xs font-medium text-red-700">{errors.scope}</p> : null}
                <button type="button" disabled={scope.length >= 200} onClick={() => setScope([...scope, { node_type: "zone", node_id: "" }])} className="rounded border border-slate-300 px-3 py-1 text-sm">{t("def.add_scope")}</button>
              </fieldset>
            </>
          )}
          <ReasonField value={reason} onChange={setReason} error={errors.reason} />
          <div className="flex gap-2">
            <button type="button" disabled={busy} onClick={submit} data-testid="def-submit" className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">{t("common.save")}</button>
            <button type="button" disabled={busy} onClick={() => setOpen(false)} className="rounded border border-slate-300 px-4 py-2 text-sm">{t("common.cancel")}</button>
          </div>
        </section>
      ) : null}
      {message ? (
        <p role={message.ok ? "status" : "alert"} data-testid="def-message" className={`rounded p-2 text-sm ${message.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>{message.text}</p>
      ) : null}
    </div>
  );
}
