"use client";
// Assign an MDM policy to one device (or clear it): one audited PUT, which queues an apply_policy command.
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import { Field, inputClass } from "../kit/field";
import { REASON_MIN_LENGTH } from "../kit/reason-field";

export function PolicyAssign({ deviceId, current, policies }: { deviceId: number; current: number | null; policies: { id: number; name: string }[] }) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [value, setValue] = useState(current === null ? "" : String(current));
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ kind: "ok" | "error"; text: string } | null>(null);
  const [err, setErr] = useState<string | null>(null);

  async function save() {
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) return setErr(t("admin.reason.too_short"));
    setErr(null);
    setBusy(true);
    setMsg(null);
    try {
      const res = await fetch("/api/bff/admin-op", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({ op: "mdm.assign", params: { device_id: String(deviceId) }, body: { policy_id: value === "" ? null : Number(value) }, reason: reason.trim() }),
      });
      const data = (await res.json().catch(() => ({}))) as Partial<Problem>;
      if (res.ok) {
        setMsg({ kind: "ok", text: t("mdm.policy.assigned") });
        setReason("");
        router.refresh();
      } else setMsg({ kind: "error", text: problem(data.code) });
    } catch {
      setMsg({ kind: "error", text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-3" data-testid="mdm-assign">
      <Field label={t("mdm.col.policy")} htmlFor="mdm-assign-policy">
        <select id="mdm-assign-policy" value={value} onChange={(e) => setValue(e.target.value)} className={inputClass}>
          <option value="">{t("mdm.policy.none")}</option>
          {policies.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </Field>
      <Field label={t("admin.reason.label")} htmlFor="mdm-assign-reason" hint={t("admin.reason.hint")} error={err}>
        <textarea id="mdm-assign-reason" rows={2} maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} className={inputClass} />
      </Field>
      <button type="button" disabled={busy} onClick={save} data-testid="mdm-assign-save" className="rounded-full bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
        {t("mdm.policy.assign")}
      </button>
      {msg ? (
        <p role={msg.kind === "error" ? "alert" : "status"} data-testid={msg.kind === "ok" ? "form-ok" : "form-error"} className={`text-sm ${msg.kind === "ok" ? "text-[var(--success)]" : "text-[var(--danger)]"}`}>
          {msg.text}
        </p>
      ) : null}
    </div>
  );
}
