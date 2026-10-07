"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import type { MessageKey } from "@/lib/i18n";
import { inputClass } from "./kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "./kit/reason-field";

export interface EditorItem {
  code: string;
  label_en: string;
  label_bn: string;
  sort: string;
  valid_from: string;
  valid_to: string;
  attrs: Record<string, string>;
  /** Saved items have an immutable code. */
  saved: boolean;
}

export interface EditorAttr {
  key: string;
  label: string;
  options?: string[];
}

interface Props {
  listKey: string;
  items: EditorItem[];
  attrs: EditorAttr[];
  canWrite: boolean;
}

const CODE_RE = /^[a-z][a-z0-9_]{1,40}$/;

/** Whole-list editor: existing codes are locked, labels and attributes editable, items retired by a date, new rows added. */
export function CodeListEditor({ listKey, items: initial, attrs, canWrite }: Props) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [items, setItems] = useState<EditorItem[]>(initial);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [banner, setBanner] = useState<{ ok: boolean; text: string } | null>(null);
  const [rowErrors, setRowErrors] = useState<Record<number, string>>({});
  const [reasonError, setReasonError] = useState<string | null>(null);

  const patch = (i: number, p: Partial<EditorItem>) => setItems(items.map((it, k) => (k === i ? { ...it, ...p } : it)));

  async function save() {
    setBanner(null);
    setRowErrors({});
    setReasonError(null);
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) {
      setReasonError(t("admin.reason.too_short"));
      return;
    }
    const errs: Record<number, string> = {};
    items.forEach((it, i) => {
      if (!CODE_RE.test(it.code) || !it.label_en.trim() || !/^-?\d+$/.test(it.sort.trim())) errs[i] = t("codelist.row_error", { n: i + 1 });
    });
    if (Object.keys(errs).length) {
      setRowErrors(errs);
      return;
    }
    setBusy(true);
    try {
      const body = {
        reason: reason.trim(),
        items: items.map((it) => ({
          code: it.code,
          label_en: it.label_en.trim(),
          label_bn: it.label_bn.trim() === "" ? null : it.label_bn.trim(),
          sort: Number(it.sort),
          ...(it.valid_from ? { valid_from: it.valid_from } : {}),
          valid_to: it.valid_to === "" ? null : it.valid_to,
          attrs: Object.fromEntries(Object.entries(it.attrs).filter(([, v]) => v !== "")),
        })),
      };
      const res = await fetch(`/api/bff/admin/code-lists/${listKey}`, { method: "PUT", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify(body) });
      const data = (await res.json().catch(() => ({}))) as Partial<Problem>;
      if (res.ok) {
        setReason("");
        setBanner({ ok: true, text: t("codelist.saved") });
        router.refresh();
        return;
      }
      const e2: Record<number, string> = {};
      for (const e of data.errors ?? []) {
        const m = /^\/items\/(\d+)/.exec(e.pointer);
        if (m) e2[Number(m[1])] = t("codelist.row_error", { n: Number(m[1]) + 1 });
      }
      setRowErrors(e2);
      const removed = (data.errors ?? []).some((e) => e.code === "code_removed");
      setBanner({ ok: false, text: removed ? t("codelist.removed") : problem(data.code) });
    } catch {
      setBanner({ ok: false, text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  const th = "px-2 py-2 text-left font-semibold text-slate-700";
  return (
    <div className="space-y-4" data-testid="codelist-editor">
      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full text-sm">
          <caption className="sr-only">{listKey}</caption>
          <thead className="bg-slate-50">
            <tr>
              <th className={th}>{t("codelist.col.code")}</th>
              <th className={th}>{t("codelist.col.label_en")}</th>
              <th className={th}>{t("codelist.col.label_bn")}</th>
              <th className={th}>{t("codelist.col.sort")}</th>
              {attrs.map((a) => (
                <th key={a.key} className={th}>
                  {a.label}
                </th>
              ))}
              <th className={th}>{t("codelist.col.valid_to")}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {items.map((it, i) => (
              <tr key={i} data-testid="codelist-row" aria-invalid={rowErrors[i] ? true : undefined} className={rowErrors[i] ? "bg-red-50" : undefined}>
                <td className="px-2 py-1">
                  <input aria-label={t("codelist.col.code")} value={it.code} readOnly={it.saved || !canWrite} title={it.saved ? t("codelist.code_locked") : undefined} onChange={(e) => patch(i, { code: e.target.value })} className={`${inputClass} font-mono ${it.saved ? "bg-slate-100" : ""}`} />
                </td>
                <td className="px-2 py-1">
                  <input aria-label={t("codelist.col.label_en")} value={it.label_en} readOnly={!canWrite} maxLength={120} onChange={(e) => patch(i, { label_en: e.target.value })} className={inputClass} />
                </td>
                <td className="px-2 py-1">
                  <input aria-label={t("codelist.col.label_bn")} value={it.label_bn} readOnly={!canWrite} maxLength={120} onChange={(e) => patch(i, { label_bn: e.target.value })} className={inputClass} />
                </td>
                <td className="px-2 py-1">
                  <input aria-label={t("codelist.col.sort")} value={it.sort} readOnly={!canWrite} inputMode="numeric" onChange={(e) => patch(i, { sort: e.target.value })} className={`${inputClass} w-20`} />
                </td>
                {attrs.map((a) => (
                  <td key={a.key} className="px-2 py-1">
                    {a.options ? (
                      <select aria-label={a.label} value={it.attrs[a.key] ?? ""} disabled={!canWrite} onChange={(e) => patch(i, { attrs: { ...it.attrs, [a.key]: e.target.value } })} className={inputClass}>
                        <option value="">{t("common.none")}</option>
                        {a.options.map((o) => (
                          <option key={o} value={o}>
                            {o}
                          </option>
                        ))}
                      </select>
                    ) : (
                      <input aria-label={a.label} value={it.attrs[a.key] ?? ""} readOnly={!canWrite} onChange={(e) => patch(i, { attrs: { ...it.attrs, [a.key]: e.target.value } })} className={inputClass} />
                    )}
                  </td>
                ))}
                <td className="px-2 py-1">
                  <input aria-label={t("codelist.col.valid_to")} type="date" value={it.valid_to} readOnly={!canWrite} onChange={(e) => patch(i, { valid_to: e.target.value })} className={inputClass} />
                  {rowErrors[i] ? (
                    <p role="alert" className="text-xs text-red-700">
                      {rowErrors[i]}
                    </p>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {canWrite ? (
        <>
          <button type="button" data-testid="codelist-add" onClick={() => setItems([...items, { code: "", label_en: "", label_bn: "", sort: String((items.reduce((m, x) => Math.max(m, Number(x.sort) || 0), 0)) + 1), valid_from: "", valid_to: "", attrs: {}, saved: false }])} className="rounded border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100">
            {t("codelist.add")}
          </button>
          <ReasonField value={reason} onChange={setReason} error={reasonError} />
          {banner ? (
            <p role={banner.ok ? "status" : "alert"} data-testid={banner.ok ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>
              {banner.text}
            </p>
          ) : null}
          <button type="button" disabled={busy} onClick={save} data-testid="codelist-save" className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
            {t("common.save")}
          </button>
        </>
      ) : (
        <p className="text-sm text-slate-600">{t("admin.read_only")}</p>
      )}
    </div>
  );
}

export type { MessageKey };
