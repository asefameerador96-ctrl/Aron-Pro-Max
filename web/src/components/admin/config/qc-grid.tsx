"use client";
// F-WEB-052 market QC and F-WEB-060 warehouse QC: SKU by fault type, saved as a separate web source (never added to app QC).
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import { buildQcRows } from "@/lib/admin/qc-entry";
import { inputClass } from "../kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "../kit/reason-field";

export interface QcSku {
  id: number;
  label: string;
}
export interface QcFault {
  code: string;
  label: string;
  group: string;
}

export function QcGrid({ source, zoneId, routeId, date, skus, faults }: { source: "market" | "warehouse"; zoneId: number; routeId?: number; date: string; skus: QcSku[]; faults: QcFault[] }) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [cells, setCells] = useState<Record<string, string>>({});
  const [reason, setReason] = useState("");
  const [uuid, setUuid] = useState(() => crypto.randomUUID());
  const [bad, setBad] = useState<string[]>([]);
  const [err, setErr] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  async function save() {
    setBanner(null);
    const { rows, errors } = buildQcRows(cells);
    const e: Record<string, string> = {};
    if (errors.length) e.cells = t("error.field.invalid");
    if (rows.length === 0 && errors.length === 0) e.cells = t("qcg.nothing");
    if (source === "warehouse" && Array.from(reason.trim()).length < REASON_MIN_LENGTH) e.reason = t("admin.reason.too_short");
    setBad(errors);
    setErr(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    try {
      const body = { client_uuid: uuid, source, business_date: date, zone_id: zoneId, ...(source === "market" ? { route_id: routeId } : {}), rows };
      const res = await fetch("/api/bff/team-op", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ op: "qc-entry.save", body, ...(source === "warehouse" ? { reason: reason.trim() } : {}) }) });
      const data = (await res.json().catch(() => ({}))) as Partial<Problem>;
      if (res.ok) {
        setBanner({ ok: true, text: t("qcg.saved") });
        setCells({});
        setReason("");
        setUuid(crypto.randomUUID());
        router.refresh();
      } else setBanner({ ok: false, text: problem(data.code) });
    } catch {
      setBanner({ ok: false, text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  const cell = "px-1 py-1 align-top";
  return (
    <div className="space-y-3" data-testid={`qc-grid-${source}`}>
      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full text-sm">
          <thead className="bg-slate-50 text-left">
            <tr>
              <th className={cell}>{t("we.col.sku")}</th>
              {faults.map((f) => (
                <th key={f.code} className={`${cell} text-right`} title={f.group}>
                  {f.label}
                  <span className="block text-[10px] font-normal text-slate-500">{f.group}</span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {skus.map((s) => (
              <tr key={s.id}>
                <td className={cell}>{s.label}</td>
                {faults.map((f) => {
                  const k = `${s.id}|${f.code}`;
                  return (
                    <td key={f.code} className={cell}>
                      <input aria-label={`${s.label} ${f.label}`} value={cells[k] ?? ""} inputMode="numeric" onChange={(ev) => setCells((c) => ({ ...c, [k]: ev.target.value.replace(/\D/g, "").slice(0, 8) }))} aria-invalid={bad.includes(k) ? true : undefined} className={`${inputClass} w-20 text-right`} />
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {err.cells ? <p role="alert" className="text-xs text-red-700">{err.cells}</p> : null}
      {source === "warehouse" ? <ReasonField value={reason} onChange={setReason} error={err.reason} /> : null}
      {banner ? <p role={banner.ok ? "status" : "alert"} data-testid={banner.ok ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>{banner.text}</p> : null}
      <button type="button" disabled={busy} onClick={save} className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">{t("common.save")}</button>
    </div>
  );
}
