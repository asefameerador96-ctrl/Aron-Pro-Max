"use client";
import { useRouter } from "next/navigation";
import { useMemo, useRef, useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { formatPriceMtk, parseTakaToMtk } from "@/lib/admin/price-money";
import { PRICE_TYPES, type PriceType } from "@/lib/admin/price-types";
import { callMasterOp } from "./master-op-client";
import { Field, inputClass } from "./kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "./kit/reason-field";


export interface PriceSku {
  id: number;
  code: string;
  name: string;
  /** Current price in force per type, in milli-taka. */
  current: Partial<Record<PriceType, number>>;
  /** The price in force is for this many base units (sticks, pieces, dozens); omitted means 1. A new price keeps the same basis. */
  perBase?: Partial<Record<PriceType, number>>;
}

/** The day after an ISO business date: a publish is from a FUTURE Dhaka date, never today. */
const nextDay = (iso: string): string => {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
};

interface Preview {
  skus_affected: number;
  outlets_affected: number;
  devices_affected: number;
  max_change_pct: number;
  approval_required: boolean;
}

/**
 * Effective-dated prices for the five types, typed in taka with up to three decimals and sent as integer milli-taka.
 * Only changed cells are sent. A preview of SKUs, outlets and devices affected is mandatory before publishing, and any
 * further edit invalidates it (a new batch_uuid is minted), so what is confirmed is exactly what was previewed.
 */
export function PriceGrid({ skus, minDate, canWrite, typeLabels }: { skus: PriceSku[]; minDate: string; canWrite: boolean; typeLabels: Record<PriceType, string> }) {
  const { t, locale, number, problem } = useI18n();
  const router = useRouter();
  const [cells, setCells] = useState<Record<string, string>>({});
  const earliest = nextDay(minDate);
  const [validFrom, setValidFrom] = useState(earliest);
  const [reason, setReason] = useState("");
  const [preview, setPreview] = useState<Preview | null>(null);
  const [busy, setBusy] = useState(false);
  const [banner, setBanner] = useState<{ ok: boolean; text: string } | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const batch = useRef<string>(crypto.randomUUID());

  const key = (sku: number, type: PriceType) => `${sku}:${type}`;
  const rows = useMemo(() => {
    const out: { sku_id: number; price_type: PriceType; amount_mtk: number; per_base_qty?: number }[] = [];
    const bad: Record<string, string> = {};
    for (const s of skus) {
      for (const ty of PRICE_TYPES) {
        const raw = cells[key(s.id, ty)];
        if (raw === undefined || raw.trim() === "") continue;
        const mtk = parseTakaToMtk(raw);
        if (mtk === null) {
          bad[key(s.id, ty)] = t("prices.bad_amount");
          continue;
        }
        if (mtk !== s.current[ty]) {
          const pb = s.perBase?.[ty];
          out.push({ sku_id: s.id, price_type: ty, amount_mtk: mtk, ...(pb && pb > 1 ? { per_base_qty: pb } : {}) }); // keep the pack basis the admin saw
        }
      }
    }
    return { changes: out, bad };
  }, [cells, skus, t]);

  const edit = (k: string, v: string) => {
    setCells({ ...cells, [k]: v });
    setPreview(null); // an edit invalidates the preview
    batch.current = crypto.randomUUID();
    setBanner(null);
  };

  function validate(): boolean {
    const e: Record<string, string> = {};
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) e.reason = t("admin.reason.too_short");
    if (!validFrom || validFrom < earliest) e.valid_from = t("error.field.past");
    if (rows.changes.length === 0) e.cells = t("prices.nothing");
    if (Object.keys(rows.bad).length) e.cells = t("prices.bad_amount");
    setErrors(e);
    return Object.keys(e).length === 0;
  }

  const body = () => ({ batch_uuid: batch.current, valid_from: validFrom, prices: rows.changes });

  async function runPreview() {
    if (!validate()) return;
    setBusy(true);
    const r = await callMasterOp<Preview>("price.preview", { body: body(), reason: reason.trim() });
    setBusy(false);
    if (r.ok) setPreview(r.data);
    else setBanner({ ok: false, text: problem(r.problem.code) });
  }

  async function publish() {
    if (!preview || !validate()) return;
    setBusy(true);
    const needsApproval = preview.approval_required; // the 201 answer is a SkuPriceList with no status: the preview told us
    const r = await callMasterOp<{ status?: string }>("price.publish", { body: body(), reason: reason.trim() });
    setBusy(false);
    if (r.ok) {
      setBanner({ ok: true, text: needsApproval || r.data?.status === "pending_approval" ? t("prices.pending_approval") : t("prices.published") });
      setCells({});
      setPreview(null);
      setReason("");
      batch.current = crypto.randomUUID();
      router.refresh();
    } else {
      setBanner({ ok: false, text: problem(r.problem.code) });
    }
  }

  return (
    <div className="space-y-4" data-testid="prices">
      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full text-sm">
          <caption className="sr-only">{t("prices.title")}</caption>
          <thead className="bg-slate-50">
            <tr>
              <th className="px-3 py-2 text-left font-semibold">{t("entity.field.code")}</th>
              <th className="px-3 py-2 text-left font-semibold">{t("entity.field.name")}</th>
              {PRICE_TYPES.map((ty) => (
                <th key={ty} className="px-3 py-2 text-right font-semibold">
                  {typeLabels[ty]}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {skus.map((s) => (
              <tr key={s.id} data-testid="price-row">
                <td className="px-3 py-2">{s.code}</td>
                <td className="px-3 py-2">{s.name}</td>
                {PRICE_TYPES.map((ty) => {
                  const k = key(s.id, ty);
                  const cur = s.current[ty];
                  return (
                    <td key={ty} className="px-2 py-1 text-right">
                      {(s.perBase?.[ty] ?? 1) > 1 ? <span className="block text-xs text-slate-500">{t("prices.per_base", { n: number(s.perBase![ty]!) })}</span> : null}
                      <input
                        aria-label={`${s.code} ${typeLabels[ty]}`}
                        inputMode="decimal"
                        disabled={!canWrite}
                        placeholder={cur === undefined ? "—" : formatPriceMtk(locale, cur)}
                        value={cells[k] ?? ""}
                        aria-invalid={rows.bad[k] ? true : undefined}
                        onChange={(e) => edit(k, e.target.value)}
                        className={`${inputClass} w-28 text-right ${rows.bad[k] ? "border-red-500" : ""}`}
                      />
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {errors.cells ? (
        <p role="alert" className="text-sm text-red-700">
          {errors.cells}
        </p>
      ) : null}
      <p aria-live="polite" className="text-sm font-semibold" data-testid="price-changes">
        {t("prices.changes", { n: rows.changes.length })}
      </p>
      {canWrite ? (
        <>
          <Field label={t("entity.field.valid_from")} htmlFor="f-valid_from" required error={errors.valid_from}>
            <input id="f-valid_from" type="date" min={earliest} value={validFrom} onChange={(e) => { setValidFrom(e.target.value); setPreview(null); batch.current = crypto.randomUUID(); }} className={`${inputClass} w-48`} />
          </Field>
          <ReasonField value={reason} onChange={(v) => { setReason(v); setPreview(null); }} error={errors.reason} />
          <div className="flex flex-wrap gap-3">
            <button type="button" disabled={busy} onClick={runPreview} data-testid="price-preview" className="rounded border border-brand-600 px-4 py-2 text-sm font-semibold text-brand-700 hover:bg-brand-50 disabled:opacity-50">
              {t("prices.preview")}
            </button>
            <button type="button" disabled={busy || !preview} onClick={publish} data-testid="price-publish" className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
              {t("prices.publish")}
            </button>
          </div>
          {preview ? (
            <dl className="grid grid-cols-2 gap-2 rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm md:grid-cols-4" data-testid="price-preview-result">
              <div>
                <dt className="text-slate-600">{t("prices.skus_affected")}</dt>
                <dd className="text-lg font-semibold">{number(preview.skus_affected)}</dd>
              </div>
              <div>
                <dt className="text-slate-600">{t("prices.outlets_affected")}</dt>
                <dd className="text-lg font-semibold">{number(preview.outlets_affected)}</dd>
              </div>
              <div>
                <dt className="text-slate-600">{t("prices.devices_affected")}</dt>
                <dd className="text-lg font-semibold">{number(preview.devices_affected)}</dd>
              </div>
              <div>
                <dt className="text-slate-600">{t("prices.max_change")}</dt>
                <dd className="text-lg font-semibold">{number(preview.max_change_pct)}%</dd>
              </div>
              {preview.approval_required ? (
                <p className="col-span-full font-semibold text-amber-900" data-testid="price-needs-approval">
                  {t("prices.approval_required")}
                </p>
              ) : null}
            </dl>
          ) : null}
        </>
      ) : (
        <p className="text-sm text-slate-600">{t("admin.read_only")}</p>
      )}
      {banner ? (
        <p role={banner.ok ? "status" : "alert"} data-testid={banner.ok ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>
          {banner.text}
        </p>
      ) : null}
      <p className="text-xs text-slate-500">{t("prices.note", { example: formatPriceMtk(locale, 12500) })}</p>
    </div>
  );
}
