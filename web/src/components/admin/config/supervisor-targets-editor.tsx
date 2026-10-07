"use client";
// F-ADM-025 supervisory module: AMO call targets by month (total, control-call, joint-call, optional daily). One save = one
// all-or-nothing PUT with a batch uuid (a retry replays) and a mandatory reason.
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import type { SupervisorTarget } from "@/lib/admin/types";
import { digitsOnly } from "@/lib/admin/taka";
import { inputClass } from "../kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "../kit/reason-field";

const FIELDS = ["total_call_target", "control_call_target", "joint_call_target", "daily_call_target"] as const;
type F = (typeof FIELDS)[number];

export function SupervisorTargetsEditor({ month, initial: stored, names, canWrite }: { month: string; initial: SupervisorTarget[]; names: Record<string, string>; canWrite: boolean }) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [initial, setInitial] = useState<SupervisorTarget[]>(stored);
  const [newId, setNewId] = useState("");
  const [rows, setRows] = useState<Record<number, Record<F, string>>>(() => Object.fromEntries(stored.map((r) => [r.user_id, { total_call_target: String(r.total_call_target), control_call_target: String(r.control_call_target), joint_call_target: String(r.joint_call_target), daily_call_target: r.daily_call_target == null ? "" : String(r.daily_call_target) }])));
  const [reason, setReason] = useState("");
  const [batch, setBatch] = useState(() => crypto.randomUUID());
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  function addOfficer() {
    if (!/^[1-9]\d{0,14}$/.test(newId) || initial.some((r) => r.user_id === Number(newId))) return setErrors({ add: t("error.field.invalid") });
    const id = Number(newId);
    setInitial((s) => [...s, { user_id: id, month, total_call_target: 0, control_call_target: 0, joint_call_target: 0, daily_call_target: null }]);
    setRows((s) => ({ ...s, [id]: { total_call_target: "0", control_call_target: "0", joint_call_target: "0", daily_call_target: "" } }));
    setNewId("");
    setErrors({});
  }

  async function save() {
    const e: Record<string, string> = {};
    const out: SupervisorTarget[] = [];
    for (const r of initial) {
      const v = rows[r.user_id]!;
      const nums: Partial<Record<F, number | null>> = {};
      for (const f of FIELDS) {
        const raw = v[f].trim();
        if (f === "daily_call_target" && raw === "") nums[f] = null;
        else if (!/^\d{1,6}$/.test(raw)) e[`${r.user_id}.${f}`] = t("error.field.invalid");
        else nums[f] = Number(raw);
      }
      if (!Object.keys(e).some((k) => k.startsWith(`${r.user_id}.`))) {
        const total = nums.total_call_target ?? 0;
        if ((nums.control_call_target ?? 0) + (nums.joint_call_target ?? 0) > total) e[`${r.user_id}.total_call_target`] = t("st.sum_error");
        out.push({ user_id: r.user_id, month, total_call_target: total, control_call_target: nums.control_call_target ?? 0, joint_call_target: nums.joint_call_target ?? 0, daily_call_target: nums.daily_call_target ?? null });
      }
    }
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) e.reason = t("admin.reason.too_short");
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    setBanner(null);
    try {
      const res = await fetch("/api/bff/admin-op", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ op: "supervisor-targets.put", body: { batch_uuid: batch, rows: out }, reason: reason.trim() }) });
      const data = (await res.json().catch(() => ({}))) as Partial<Problem>;
      if (res.ok) {
        setBanner({ ok: true, text: t("st.saved") });
        setBatch(crypto.randomUUID());
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
  return (
    <div className="space-y-3" data-testid="targets-editor">
      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full text-sm">
          <thead className="bg-slate-50 text-left">
            <tr>
              <th className={cell}>{t("cfgc.col.user")}</th>
              {FIELDS.map((f) => (<th key={f} className={cell}>{t(`st.col.${f}` as "st.col.total_call_target")}</th>))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {initial.map((r) => (
              <tr key={r.user_id} data-testid={`target-${r.user_id}`}>
                <td className={cell}>{names[String(r.user_id)] ?? r.user_id}</td>
                {FIELDS.map((f) => (
                  <td key={f} className={cell}>
                    <input aria-label={t(`st.col.${f}` as "st.col.total_call_target")} value={rows[r.user_id]![f]} disabled={!canWrite} inputMode="numeric" onChange={(ev) => setRows((s) => ({ ...s, [r.user_id]: { ...s[r.user_id]!, [f]: ev.target.value } }))} className={`${inputClass} w-24`} />
                    {errors[`${r.user_id}.${f}`] ? <p role="alert" className="text-xs text-red-700">{errors[`${r.user_id}.${f}`]}</p> : null}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {canWrite ? (
        <div className="flex items-end gap-2" data-testid="add-officer">
          <label className="text-sm">
            <span className="mb-1 block">{t("st.add_user")}</span>
            <input aria-label={t("st.add_user")} name="user_id" value={newId} inputMode="numeric" onChange={(e) => setNewId(digitsOnly(e.target.value))} className={`${inputClass} w-32`} />
          </label>
          <button type="button" onClick={addOfficer} className="rounded border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100">{t("st.add")}</button>
          {errors.add ? <p role="alert" className="text-xs text-red-700">{errors.add}</p> : null}
        </div>
      ) : null}
      {canWrite ? (
        <>
          <ReasonField value={reason} onChange={setReason} error={errors.reason} />
          {banner ? <p role={banner.ok ? "status" : "alert"} data-testid={banner.ok ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>{banner.text}</p> : null}
          <button type="button" onClick={save} disabled={busy || initial.length === 0} className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">{t("common.save")}</button>
        </>
      ) : null}
    </div>
  );
}
