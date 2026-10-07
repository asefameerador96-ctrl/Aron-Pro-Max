"use client";
import { useEffect, useMemo, useRef, useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import { inputClass } from "./kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "./kit/reason-field";

export interface BasketRow {
  id: number;
  code: string;
  name: string;
  owner: string;
  kind: "retail" | "wholesale";
  kindLabel: string;
}

const STORE = "aron.wholesale.basket";
const MAX = 5000; // OutletKindBulkWrite.outlet_ids maxItems

function readStore(): number[] {
  try {
    const v = JSON.parse(sessionStorage.getItem(STORE) ?? "[]") as unknown;
    return Array.isArray(v) ? v.filter((x): x is number => Number.isInteger(x)) : [];
  } catch {
    return [];
  }
}

/** Basket of outlets to mark wholesale (or back to retail): live count, one confirm step, idempotent by batch_uuid. */
export function WholesaleBasket({ rows }: { rows: BasketRow[] }) {
  const { t, number, problem } = useI18n();
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [target, setTarget] = useState<"wholesale" | "retail">("wholesale");
  const [reason, setReason] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [reasonError, setReasonError] = useState<string | null>(null);
  const [banner, setBanner] = useState<{ ok: boolean; text: string } | null>(null);
  // One batch_uuid per basket content: a retry of the same basket replays instead of repeating (idempotent).
  const batch = useRef<{ key: string; uuid: string } | null>(null);

  useEffect(() => {
    // sessionStorage exists only in the browser, so the saved basket can only be read after hydration.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSelected(new Set(readStore()));
  }, []);
  useEffect(() => {
    try {
      sessionStorage.setItem(STORE, JSON.stringify([...selected]));
    } catch {
      /* storage may be unavailable; the basket then lives only on this page */
    }
  }, [selected]);

  const shownIds = useMemo(() => rows.map((r) => r.id), [rows]);
  const toggle = (id: number) => {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else if (next.size < MAX) next.add(id);
    setSelected(next);
    setConfirming(false);
  };

  async function submit() {
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) {
      setReasonError(t("admin.reason.too_short"));
      return;
    }
    setReasonError(null);
    const ids = [...selected].sort((a, b) => a - b);
    const key = `${target}:${ids.join(",")}`;
    if (batch.current?.key !== key) batch.current = { key, uuid: crypto.randomUUID() };
    setBusy(true);
    setBanner(null);
    try {
      const res = await fetch("/api/bff/admin/wholesale-marking", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ batch_uuid: batch.current.uuid, outlet_kind: target, outlet_ids: ids, reason: reason.trim() }) });
      const data = (await res.json().catch(() => ({}))) as { updated?: number; unchanged?: number; replayed?: boolean } & Partial<Problem>;
      if (res.ok) {
        setBanner({ ok: true, text: t("wholesale.done", { updated: data.updated ?? 0, unchanged: data.unchanged ?? 0 }) });
        setSelected(new Set());
        setConfirming(false);
        setReason("");
        batch.current = null;
        return;
      }
      setBanner({ ok: false, text: problem(data.code) });
    } catch {
      setBanner({ ok: false, text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  const count = selected.size;
  return (
    <div className="space-y-4" data-testid="wholesale">
      <p aria-live="polite" className="text-sm font-semibold text-slate-800" data-testid="basket-count">
        {t("wholesale.count", { n: count })}
      </p>
      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full text-sm">
          <caption className="sr-only">{t("wholesale.title")}</caption>
          <thead className="bg-slate-50">
            <tr>
              <th className="px-3 py-2 text-left">
                <input
                  type="checkbox"
                  aria-label={t("wholesale.select_all")}
                  checked={shownIds.length > 0 && shownIds.every((id) => selected.has(id))}
                  onChange={(e) => {
                    const next = new Set(selected);
                    for (const id of shownIds) {
                      if (e.target.checked) next.add(id);
                      else next.delete(id);
                    }
                    setSelected(next);
                    setConfirming(false);
                  }}
                />
              </th>
              <th className="px-3 py-2 text-left font-semibold">{t("entity.field.code")}</th>
              <th className="px-3 py-2 text-left font-semibold">{t("entity.field.name")}</th>
              <th className="px-3 py-2 text-left font-semibold">{t("entity.field.owner_name")}</th>
              <th className="px-3 py-2 text-left font-semibold">{t("entity.field.outlet_kind")}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {rows.map((r) => (
              <tr key={r.id} data-testid="basket-row">
                <td className="px-3 py-2">
                  <input type="checkbox" aria-label={`${r.code} ${r.name}`} checked={selected.has(r.id)} onChange={() => toggle(r.id)} />
                </td>
                <td className="px-3 py-2">{r.code}</td>
                <td className="px-3 py-2">{r.name}</td>
                <td className="px-3 py-2">{r.owner}</td>
                <td className="px-3 py-2">{r.kindLabel}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <label className="text-sm">
          <span className="mb-1 block">{t("wholesale.mark_as")}</span>
          <select value={target} onChange={(e) => { setTarget(e.target.value as "wholesale" | "retail"); setConfirming(false); }} className={`${inputClass} w-48`}>
            <option value="wholesale">{t("outlet.kind.wholesale")}</option>
            <option value="retail">{t("outlet.kind.retail")}</option>
          </select>
        </label>
        <button type="button" disabled={count === 0 || busy} data-testid="basket-continue" onClick={() => setConfirming(true)} className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
          {t("wholesale.continue")}
        </button>
        <button type="button" disabled={count === 0} onClick={() => { setSelected(new Set()); setConfirming(false); }} className="rounded border border-slate-300 px-3 py-2 text-sm hover:bg-slate-100 disabled:opacity-50">
          {t("wholesale.clear")}
        </button>
      </div>

      {confirming ? (
        <section role="dialog" aria-modal="false" aria-labelledby="confirm-title" data-testid="basket-confirm" className="space-y-3 rounded-lg border border-amber-300 bg-amber-50 p-4">
          <h2 id="confirm-title" className="font-semibold text-amber-900">
            {t("wholesale.confirm", { n: count, kind: t(target === "wholesale" ? "outlet.kind.wholesale" : "outlet.kind.retail") })}
          </h2>
          <ReasonField value={reason} onChange={setReason} error={reasonError} />
          <div className="flex gap-3">
            <button type="button" disabled={busy} data-testid="basket-submit" onClick={submit} className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
              {t("wholesale.submit")}
            </button>
            <button type="button" onClick={() => setConfirming(false)} className="rounded border border-slate-300 px-4 py-2 text-sm hover:bg-slate-100">
              {t("common.cancel")}
            </button>
          </div>
        </section>
      ) : null}
      {banner ? (
        <p role={banner.ok ? "status" : "alert"} data-testid={banner.ok ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>
          {banner.text}
        </p>
      ) : null}
      <p className="text-xs text-slate-500">{t("wholesale.shown", { n: number(rows.length) })}</p>
    </div>
  );
}
