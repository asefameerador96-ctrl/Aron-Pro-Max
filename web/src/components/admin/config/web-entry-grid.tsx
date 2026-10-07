"use client";
// F-WEB-050 per-SKU grid of one route-day: Issue, Return, Sale (read-only), Memos, and Successful Calls (at most the target outlets).
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import { buildEntry, saleOf, type EntryRow } from "@/lib/admin/web-entry";
import { digitsOnly } from "@/lib/admin/taka";
import { inputClass } from "../kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "../kit/reason-field";

export interface GridSku {
  id: number;
  label: string;
}

export function WebEntryGrid({ routeId, date, skus: listed, initialLines, targetOutlets, initialCalls, saved, appOverlap, canWrite, classes = [] }: { routeId: number; date: string; skus: GridSku[]; initialLines: { sku_id: number; issue_qty_base: number; return_qty_base: number; memo_count: number; class_qty_base?: Record<string, number> }[]; classes?: number[]; targetOutlets: number; initialCalls: number; saved: boolean; appOverlap: boolean; canWrite: boolean }) {
  const { t, problem, number } = useI18n();
  // Stored lines of a SKU that is not in the list (made inactive, or beyond the list limit) stay in the grid: a re-save replaces the whole entry and must not drop them.
  const skus: GridSku[] = [...listed, ...initialLines.filter((l) => !listed.some((s) => s.id === l.sku_id)).map((l) => ({ id: l.sku_id, label: `#${l.sku_id}` }))];
  const router = useRouter();
  const [rows, setRows] = useState<EntryRow[]>(() => skus.map((s) => {
    const l = initialLines.find((x) => x.sku_id === s.id);
    return { sku_id: s.id, issue: l ? String(l.issue_qty_base) : "", ret: l ? String(l.return_qty_base) : "", memos: l ? String(l.memo_count) : "", cls: Object.fromEntries(classes.map((c) => [String(c), l?.class_qty_base?.[String(c)] !== undefined ? String(l.class_qty_base[String(c)]) : ""])) };
  }));
  const [calls, setCalls] = useState(String(initialCalls));
  const [reason, setReason] = useState("");
  const [uuid, setUuid] = useState(() => crypto.randomUUID());
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [needReason, setNeedReason] = useState(false);

  const setClass = (i: number, c: number, v: string) => setRows((s) => s.map((r, j) => (j === i ? { ...r, cls: { ...r.cls, [String(c)]: digitsOnly(v).slice(0, 8) } } : r)));
  const set = (i: number, k: "issue" | "ret" | "memos", v: string) => setRows((s) => s.map((r, j) => (j === i ? { ...r, [k]: digitsOnly(v).slice(0, 8) } : r)));

  async function save() {
    setBanner(null);
    const { lines, errors: errs } = buildEntry(rows, calls, targetOutlets, classes);
    const e: Record<string, string> = {};
    for (const x of errs) e[`${x.row ?? "c"}.${x.field}`] = t(x.code === "class_sum" ? "we.class_sum" : x.code === "return_exceeds_issue" ? "we.return_exceeds" : x.code === "too_big" ? "cfgc.error.too_big" : "error.field.invalid");
    if ((saved || needReason) && Array.from(reason.trim()).length < REASON_MIN_LENGTH) e.reason = t("admin.reason.too_short");
    if (lines.length === 0 && Number(calls || "0") === 0 && !saved) e.empty = t("we.nothing");
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    try {
      const res = await fetch("/api/bff/team-op", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ op: "web-entry.save", body: { client_uuid: uuid, route_id: routeId, business_date: date, lines, successful_calls: Number(calls || "0") }, ...(saved || needReason ? { reason: reason.trim() } : {}) }) });
      const data = (await res.json().catch(() => ({}))) as Partial<Problem>;
      if (res.ok) {
        setBanner({ ok: true, text: t("we.saved") });
        setUuid(crypto.randomUUID());
        setReason("");
        router.refresh();
      } else {
        // Someone saved this route-day after the page loaded: the API wants the reason of a re-save, so ask for it.
        if (data.errors?.some((x) => x.pointer.includes("change_reason")) || data.code === "ERR_CFG_REASON_REQUIRED") setNeedReason(true);
        setBanner({ ok: false, text: problem(data.code) });
      }
    } catch {
      setBanner({ ok: false, text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  const cell = "px-2 py-1 align-top";
  const num = `${inputClass} w-24 text-right`;
  const err = (k: string) => (errors[k] ? <p role="alert" className="text-xs text-[var(--danger)]">{errors[k]}</p> : null);
  return (
    <div className="space-y-3" data-testid="web-entry-grid">
      {appOverlap ? <p role="status" data-testid="app-overlap" className="rounded border border-[var(--warning)] bg-[color-mix(in_srgb,var(--warning)_14%,transparent)] p-3 text-sm text-[var(--warning)]">{t("we.overlap")}</p> : null}
      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full text-sm">
          <thead className="bg-slate-50 text-left">
            <tr>
              <th className={cell}>{t("we.col.sku")}</th>
              <th className={`${cell} text-right`}>{t("we.col.issue")}</th>
              <th className={`${cell} text-right`}>{t("we.col.return")}</th>
              <th className={`${cell} text-right`}>{t("we.col.sale")}</th>
              {classes.length > 1 ? classes.map((c) => <th key={c} className={`${cell} text-right`}>{t("we.col.class", { id: number(c) })}</th>) : null}
              <th className={`${cell} text-right`}>{t("we.col.memos")}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {skus.map((s, i) => {
              const sale = saleOf(rows[i]!);
              return (
                <tr key={s.id} data-testid={`sku-${s.id}`}>
                  <td className={cell}>{s.label}</td>
                  <td className={cell}><input aria-label={`${s.label} ${t("we.col.issue")}`} value={rows[i]!.issue} disabled={!canWrite} inputMode="numeric" onChange={(ev) => set(i, "issue", ev.target.value)} className={num} />{err(`${i}.issue`)}</td>
                  <td className={cell}><input aria-label={`${s.label} ${t("we.col.return")}`} value={rows[i]!.ret} disabled={!canWrite} inputMode="numeric" onChange={(ev) => set(i, "ret", ev.target.value)} className={num} />{err(`${i}.ret`)}</td>
                  <td className={`${cell} text-right`} data-testid={`sale-${s.id}`}>{sale === null ? "—" : number(sale)}</td>
                  {classes.length > 1 ? classes.map((c) => <td key={c} className={cell}><input aria-label={`${s.label} ${t("we.col.class", { id: number(c) })}`} value={rows[i]!.cls?.[String(c)] ?? ""} disabled={!canWrite} inputMode="numeric" onChange={(ev) => setClass(i, c, ev.target.value)} className={num} /></td>) : null}
                  <td className={cell}><input aria-label={`${s.label} ${t("we.col.memos")}`} value={rows[i]!.memos} disabled={!canWrite} inputMode="numeric" onChange={(ev) => set(i, "memos", ev.target.value)} className={num} />{err(`${i}.memos`)}{err(`${i}.classes`)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <div className="max-w-xs">
        <label htmlFor="calls" className="block text-sm font-medium">{t("we.calls")}</label>
        <input id="calls" value={calls} disabled={!canWrite} inputMode="numeric" onChange={(e) => setCalls(digitsOnly(e.target.value).slice(0, 6))} className={inputClass} />
        <p className="text-xs text-slate-500">{t("we.calls.hint", { n: number(targetOutlets) })}</p>
        {err("c.calls")}
      </div>
      {errors.empty ? <p role="alert" className="text-xs text-[var(--danger)]">{errors.empty}</p> : null}
      {canWrite ? (
        <>
          {saved || needReason ? <p className="text-xs text-[var(--warning)]">{t("we.resave")}</p> : null}
          {saved || needReason ? <ReasonField value={reason} onChange={setReason} error={errors.reason} /> : null}
          {banner ? <p role={banner.ok ? "status" : "alert"} data-testid={banner.ok ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.ok ? "bg-[color-mix(in_srgb,var(--success)_14%,transparent)] text-[var(--success)]" : "bg-[color-mix(in_srgb,var(--danger)_12%,transparent)] text-[var(--danger)]"}`}>{banner.text}</p> : null}
          <button type="button" disabled={busy} onClick={save} className="rounded-full bg-brand-600 px-5 py-2.5 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">{t("common.save")}</button>
        </>
      ) : null}
    </div>
  );
}
