"use client";
// F-ADM-024: a dead-phone day keyed from the printed memo (source manual) through the same ingest path. Money in integer milli-taka.
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { digitsOnly, latinDigits } from "@/lib/admin/taka";
import type { Problem } from "@/contract/types";
import { takaToMtk } from "@/lib/admin/taka";
import type { MessageKey } from "@/lib/i18n";
import { Field, inputClass } from "../kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "../kit/reason-field";

const MEMO_NO = /^[a-z][a-z0-9]{3,31}-\d{6}-\d{3,4}$/;
const KINDS = ["sale", "drp_reward", "promo_free", "free_sample"] as const;
type Line = { sku_id: string; qty: string; kind: (typeof KINDS)[number] };

export function PaperBackfillForm() {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [v, setV] = useState({ memo_no: "", business_date: "", user_id: "", route_id: "", outlet_id: "", paid: "0" });
  const [lines, setLines] = useState<Line[]>([{ sku_id: "", qty: "", kind: "sale" }]);
  const [reason, setReason] = useState("");
  const [uuid, setUuid] = useState(() => crypto.randomUUID());
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof v, x: string) => setV((s) => ({ ...s, [k]: x }));
  const id = (x: string) => /^[1-9]\d{0,14}$/.test(latinDigits(x));

  async function save() {
    setBanner(null);
    const e: Record<string, string> = {};
    if (!MEMO_NO.test(v.memo_no)) e.memo_no = t("error.field.invalid");
    if (!/^\d{4}-\d{2}-\d{2}$/.test(v.business_date)) e.business_date = t("error.field.invalid");
    for (const k of ["user_id", "route_id", "outlet_id"] as const) if (!id(v[k])) e[k] = t("error.field.invalid");
    const paid = takaToMtk(v.paid);
    if (paid === null || paid < 0) e.paid = t("error.field.invalid");
    const body: Record<string, unknown> = { client_uuid: uuid, memo_no: v.memo_no, business_date: v.business_date, user_id: Number(latinDigits(v.user_id)), route_id: Number(latinDigits(v.route_id)), outlet_id: Number(latinDigits(v.outlet_id)), paid_mtk: paid, lines: [] as unknown[] };
    const out: { sku_id: number; qty_base: number; line_kind: string }[] = [];
    lines.forEach((l, i) => {
      if (!id(l.sku_id) || !/^\d{1,8}$/.test(l.qty) || Number(l.qty) < 1 || Number(l.qty) > 10_000_000) e[`line${i}`] = t("error.field.invalid");
      else out.push({ sku_id: Number(latinDigits(l.sku_id)), qty_base: Number(l.qty), line_kind: l.kind });
    });
    body.lines = out;
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) e.reason = t("admin.reason.too_short");
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    try {
      const res = await fetch("/api/bff/admin-op", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ op: "paper-backfill.create", body, reason: reason.trim() }) });
      const data = (await res.json().catch(() => ({}))) as { data?: { status?: string } } & Partial<Problem>;
      if (res.ok) {
        setBanner({ ok: true, text: t(`de.status.${data.data?.status ?? "created"}` as MessageKey) });
        setUuid(crypto.randomUUID());
        setReason("");
        router.refresh();
      } else setBanner({ ok: false, text: problem(data.code) });
    } catch {
      setBanner({ ok: false, text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  const field = (k: keyof typeof v, label: string, hint?: string) => (
    <Field label={label} htmlFor={`pb-${k}`} required hint={hint} error={errors[k]}>
      <input id={`pb-${k}`} type={k === "business_date" ? "date" : "text"} value={v[k]} onChange={(ev) => set(k, ev.target.value)} className={inputClass} />
    </Field>
  );
  return (
    <div className="space-y-3" data-testid="backfill-form">
      <div className="grid gap-3 sm:grid-cols-2">
        {field("memo_no", t("de.memo_no"), t("de.memo_no.hint"))}
        {field("business_date", t("cfgc.col.date"))}
        {field("user_id", t("de.user_id"))}
        {field("route_id", t("de.route_id"))}
        {field("outlet_id", t("de.outlet_id"))}
        {field("paid", t("de.paid"), t("de.paid.hint"))}
      </div>
      <div className="space-y-2">
        <p className="text-sm font-medium">{t("de.lines")}</p>
        {lines.map((l, i) => (
          <div key={i} className="flex flex-wrap items-start gap-2">
            <input aria-label={t("de.sku_id")} placeholder={t("de.sku_id")} value={l.sku_id} onChange={(ev) => setLines((s) => s.map((x, j) => (j === i ? { ...x, sku_id: ev.target.value } : x)))} className={`${inputClass} w-28`} />
            <input aria-label={t("de.qty")} placeholder={t("de.qty")} value={l.qty} inputMode="numeric" onChange={(ev) => setLines((s) => s.map((x, j) => (j === i ? { ...x, qty: digitsOnly(ev.target.value) } : x)))} className={`${inputClass} w-28`} />
            <select aria-label={t("cfgc.col.kind")} value={l.kind} onChange={(ev) => setLines((s) => s.map((x, j) => (j === i ? { ...x, kind: ev.target.value as Line["kind"] } : x)))} className={`${inputClass} w-44`}>
              {KINDS.map((k) => (<option key={k} value={k}>{t(`de.kind.${k}` as MessageKey)}</option>))}
            </select>
            {lines.length > 1 ? <button type="button" onClick={() => setLines((s) => s.filter((_, j) => j !== i))} className="rounded border border-[var(--danger)] px-2 py-1 text-xs text-[var(--danger)]">{t("ab.remove")}</button> : null}
            {errors[`line${i}`] ? <p role="alert" className="w-full text-xs text-[var(--danger)]">{errors[`line${i}`]}</p> : null}
          </div>
        ))}
        {lines.length < 60 ? <button type="button" onClick={() => setLines((s) => [...s, { sku_id: "", qty: "", kind: "sale" }])} className="rounded border border-slate-300 px-3 py-1 text-sm hover:bg-slate-100">{t("de.add_line")}</button> : null}
      </div>
      <ReasonField value={reason} onChange={setReason} error={errors.reason} />
      {banner ? <p role={banner.ok ? "status" : "alert"} data-testid={banner.ok ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.ok ? "bg-[color-mix(in_srgb,var(--success)_14%,transparent)] text-[var(--success)]" : "bg-[color-mix(in_srgb,var(--danger)_12%,transparent)] text-[var(--danger)]"}`}>{banner.text}</p> : null}
      <button type="button" disabled={busy} onClick={save} className="rounded-full bg-brand-600 px-5 py-2.5 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">{t("de.save")}</button>
    </div>
  );
}
