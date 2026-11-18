"use client";
// D-OWNER-MDM-02 iii: the lab exemption of one phone. Grants ONE phone permission to run with Developer options on for 1 to 14 days (the owner's ADB device walks).
// The server signs the grant and refuses without its signing key; the phone verifies it offline. Honest limit: it only lifts the Developer options gate, never the fake-location refusals.
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Card } from "@/components/admin/kit/page";
import { OpInline } from "@/components/admin/kit/op-inline";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import { Field, inputClass } from "../kit/field";

export interface LabExemptionState {
  deviceId: number;
  /** The unexpired exemption, null when there is none. */
  active: { expires_at: string; note?: string | null } | null;
  /** False when the server has no signing key: no exemption can be granted. */
  available: boolean;
  /** The caller may change it (Super Admin or an Admin with the mdm write grant). */
  canWrite: boolean;
}

/** 1..14 whole days, or null. */
export function parseLabDays(v: string): number | null {
  if (!/^\d{1,2}$/.test(v.trim())) return null;
  const n = Number(v);
  return n >= 1 && n <= 14 ? n : null;
}

export function LabExemptionCard({ deviceId, active, available, canWrite }: LabExemptionState) {
  const { t, problem, dateTime } = useI18n();
  const router = useRouter();
  const [days, setDays] = useState("3");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [msg, setMsg] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  async function grant() {
    const n = parseLabDays(days);
    if (n === null) {
      setErr(t("dev.lab.days_invalid"));
      return;
    }
    setErr(null);
    setBusy(true);
    setMsg(null);
    try {
      const res = await fetch("/api/bff/admin-op", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({ op: "device.lab-exemption.grant", params: { device_id: String(deviceId) }, body: { days: n, ...(note.trim() ? { note: note.trim().slice(0, 300) } : {}) } }),
      });
      const data = (await res.json().catch(() => ({}))) as Partial<Problem>;
      if (res.ok) {
        setMsg({ kind: "ok", text: t("dev.lab.granted") });
        router.refresh();
      } else setMsg({ kind: "error", text: problem(data.code) });
    } catch {
      setMsg({ kind: "error", text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title={t("dev.lab.title")} testId="lab-exemption">
      <p className="text-sm text-slate-700">{t("dev.lab.intro")}</p>
      <p className="text-sm font-medium" data-testid="lab-state">
        {active ? t("dev.lab.active", { until: dateTime(active.expires_at) }) : t("dev.lab.none")}
        {active?.note ? ` · ${active.note}` : ""}
      </p>
      {!available ? (
        <p className="text-sm text-slate-600" data-testid="lab-unavailable">
          {t("dev.lab.unavailable")}
        </p>
      ) : null}
      {canWrite && available ? (
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-[8rem_1fr]">
            <Field label={t("dev.lab.days")} htmlFor="lab-days" error={err}>
              <input id="lab-days" inputMode="numeric" value={days} onChange={(e) => setDays(e.target.value)} className={inputClass} />
            </Field>
            <Field label={t("dev.lab.note")} htmlFor="lab-note">
              <input id="lab-note" maxLength={300} value={note} onChange={(e) => setNote(e.target.value)} className={inputClass} />
            </Field>
          </div>
          <div className="flex flex-wrap gap-2">
            <button type="button" disabled={busy} data-testid="lab-grant" onClick={() => void grant()} className="rounded-full bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
              {t("dev.lab.grant")}
            </button>
            {active ? <OpInline op="device.lab-exemption.revoke" params={{ device_id: String(deviceId) }} noReason danger label={t("dev.lab.revoke")} successKey="dev.lab.withdrawn" testId="lab-revoke" /> : null}
          </div>
        </div>
      ) : null}
      {msg ? (
        <p role={msg.kind === "error" ? "alert" : "status"} data-testid={msg.kind === "ok" ? "form-ok" : "form-error"} className={`text-sm ${msg.kind === "ok" ? "text-[var(--success)]" : "text-[var(--danger)]"}`}>
          {msg.text}
        </p>
      ) : null}
    </Card>
  );
}
