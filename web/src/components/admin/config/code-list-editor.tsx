"use client";
// F-ADM-023 / F-ADM-060: edit one business code list. Codes are immutable and never deleted: a row is retired (valid_to) or
// revived; new rows add. One save = one PUT with the whole list and a mandatory reason; the audit row is written by the API.
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import { validateItems } from "@/lib/admin/code-lists";
import type { CodeItem } from "@/lib/admin/types";
import { inputClass } from "../kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "../kit/reason-field";

interface Props {
  listKey: string;
  initial: CodeItem[];
  attrs: { name: string; label: string; options: { value: string; label: string }[] }[];
  today: string;
  canWrite: boolean;
}

export function CodeListEditor({ listKey, initial, attrs, today, canWrite }: Props) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [items, setItems] = useState<CodeItem[]>(initial);
  const [reason, setReason] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const isNew = (i: number) => i >= initial.length;
  const patch = (i: number, p: Partial<CodeItem>) => setItems((s) => s.map((x, j) => (j === i ? { ...x, ...p } : x)));
  const setAttr = (i: number, name: string, v: string) => setItems((s) => s.map((x, j) => (j === i ? { ...x, attrs: { ...(x.attrs ?? {}), [name]: v } } : x)));

  function addRow() {
    const next = Math.max(0, ...items.map((x) => x.sort)) + 10;
    setItems([...items, { code: "", label_en: "", label_bn: "", sort: next, valid_from: today, valid_to: null, attrs: Object.fromEntries(attrs.map((a) => [a.name, a.options[0]?.value ?? ""])) }]);
  }

  async function save() {
    setBanner(null);
    const bad = validateItems(items, attrs.map((a) => ({ name: a.name, options: a.options.map((o) => o.value) })));
    const e: Record<string, string> = {};
    for (const b of bad) e[`${b.index}.${b.field}`] = t(b.code === "duplicate" ? "cl.duplicate" : b.code === "invalid" ? "cl.code_invalid" : "error.field.required");
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) e.reason = t("admin.reason.too_short");
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    try {
      const body = { items: items.map((x) => ({ code: x.code, label_en: x.label_en.trim(), label_bn: x.label_bn?.trim() ? x.label_bn.trim() : null, sort: x.sort, valid_from: x.valid_from, valid_to: x.valid_to ?? null, ...(x.attrs && Object.keys(x.attrs).length ? { attrs: x.attrs } : {}) })) };
      const res = await fetch("/api/bff/admin-op", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ op: "code-list.put", params: { list_key: listKey }, body, reason: reason.trim() }) });
      const data = (await res.json().catch(() => ({}))) as Partial<Problem>;
      if (res.ok) {
        setBanner({ ok: true, text: t("cl.saved") });
        setReason("");
        router.refresh();
      } else setBanner({ ok: false, text: problem(data.code) });
    } catch {
      setBanner({ ok: false, text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  const cell = "px-2 py-1 align-top";
  const err = (i: number, f: string) => (errors[`${i}.${f}`] ? <p role="alert" className="text-xs text-red-700">{errors[`${i}.${f}`]}</p> : null);
  return (
    <div className="space-y-3" data-testid={`code-list-${listKey}`}>
      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full text-sm">
          <thead className="bg-slate-50 text-left">
            <tr>
              <th className={cell}>{t("cl.code")}</th>
              <th className={cell}>{t("cal.col.name_en")}</th>
              <th className={cell}>{t("cal.col.name_bn")}</th>
              <th className={cell}>{t("cl.sort")}</th>
              {attrs.map((a) => (<th key={a.name} className={cell}>{a.label}</th>))}
              <th className={cell}>{t("cfgc.col.status")}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {items.map((it, i) => (
              <tr key={i} data-testid={`row-${it.code || `new-${i}`}`}>
                <td className={cell}>
                  {isNew(i) && canWrite ? <input aria-label={t("cl.code")} value={it.code} onChange={(e) => patch(i, { code: e.target.value })} className={inputClass} /> : <code>{it.code}</code>}
                  {err(i, "code")}
                </td>
                <td className={cell}>
                  <input aria-label={t("cal.col.name_en")} value={it.label_en} disabled={!canWrite} maxLength={120} onChange={(e) => patch(i, { label_en: e.target.value })} className={inputClass} />
                  {err(i, "label_en")}
                </td>
                <td className={cell}>
                  <input aria-label={t("cal.col.name_bn")} value={it.label_bn ?? ""} disabled={!canWrite} maxLength={120} onChange={(e) => patch(i, { label_bn: e.target.value })} className={inputClass} />
                </td>
                <td className={cell}>
                  <input aria-label={t("cl.sort")} value={String(it.sort)} disabled={!canWrite} inputMode="numeric" onChange={(e) => patch(i, { sort: Number(e.target.value.replace(/\D/g, "")) })} className={`${inputClass} w-20`} />
                </td>
                {attrs.map((a) => (
                  <td key={a.name} className={cell}>
                    <select aria-label={a.label} value={String(it.attrs?.[a.name] ?? "")} disabled={!canWrite} onChange={(e) => setAttr(i, a.name, e.target.value)} className={inputClass}>
                      {a.options.map((o) => (<option key={o.value} value={o.value}>{o.label}</option>))}
                    </select>
                    {err(i, a.name)}
                  </td>
                ))}
                <td className={cell}>
                  {it.valid_to ? <span className="text-slate-500">{t("cl.retired")}</span> : <span>{t("cl.active")}</span>}
                  {canWrite ? (
                    <button type="button" onClick={() => patch(i, { valid_to: it.valid_to ? null : today })} className="ml-2 rounded border border-slate-300 px-2 py-0.5 text-xs hover:bg-slate-100">
                      {t(it.valid_to ? "cl.revive" : "cl.retire")}
                    </button>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {canWrite ? (
        <>
          <button type="button" onClick={addRow} disabled={items.length >= 100} className="rounded border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100">{t("cl.add")}</button>
          <p className="text-xs text-slate-600">{t("cl.hint")}</p>
          <ReasonField value={reason} onChange={setReason} error={errors.reason} id={`reason-${listKey}`} />
          {banner ? <p role={banner.ok ? "status" : "alert"} data-testid={banner.ok ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>{banner.text}</p> : null}
          <button type="button" onClick={save} disabled={busy} className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">{t("common.save")}</button>
        </>
      ) : (
        <p className="text-sm text-slate-600">{t("cfgc.read_only")}</p>
      )}
    </div>
  );
}
