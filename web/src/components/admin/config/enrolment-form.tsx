"use client";
// N-045: create an enrolment token and show its QR once. The secret stays in this component's state (never stored, never logged).
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import type { EnrolmentTokenCreated } from "@/lib/admin/types";
import { Field, inputClass } from "../kit/field";
import { QrCode } from "./qr-code";

export function EnrolmentForm() {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [v, setV] = useState({ flavour: "sr", lockdown_level: "prod", zone_id: "", max_uses: "1", expires_in_h: "24", note: "", ssid: "", wifi_security: "WPA", wifi_password: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<EnrolmentTokenCreated | null>(null);
  const set = (k: keyof typeof v, x: string) => setV((s) => ({ ...s, [k]: x }));

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const local: Record<string, string> = {};
    const uses = Number(v.max_uses);
    const hours = Number(v.expires_in_h);
    if (!Number.isInteger(uses) || uses < 1 || uses > 500) local.max_uses = t("error.field.invalid");
    if (!Number.isInteger(hours) || hours < 1 || hours > 168) local.expires_in_h = t("error.field.invalid");
    if (v.zone_id !== "" && !/^[1-9]\d{0,14}$/.test(v.zone_id)) local.zone_id = t("error.field.invalid");
    if (v.ssid !== "" && v.ssid.length > 32) local.ssid = t("error.field.too_long");
    setErrors(local);
    if (Object.keys(local).length) return;
    const body: Record<string, unknown> = { flavour: v.flavour, lockdown_level: v.lockdown_level, max_uses: uses, expires_in_h: hours };
    if (v.zone_id) body.zone_id = Number(v.zone_id);
    if (v.note.trim()) body.note = v.note.trim().slice(0, 300);
    if (v.ssid) body.wifi = { ssid: v.ssid, security_type: v.wifi_security, ...(v.wifi_password ? { password: v.wifi_password } : {}) };
    setBusy(true);
    try {
      const res = await fetch("/api/bff/admin-op", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ op: "enrolment.create", body }) });
      const data = (await res.json().catch(() => ({}))) as { data?: EnrolmentTokenCreated } & Partial<Problem>;
      if (res.ok && data.data) {
        setCreated(data.data);
        setV((s) => ({ ...s, wifi_password: "" }));
        router.refresh();
      } else setError(problem(data.code));
    } catch {
      setError(t("error.network"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      <form onSubmit={onSubmit} noValidate className="grid gap-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm sm:grid-cols-2" data-testid="enrolment-form">
        <Field label={t("enr.flavour")} htmlFor="e-flavour">
          <select id="e-flavour" value={v.flavour} onChange={(e) => set("flavour", e.target.value)} className={inputClass}>
            {["sr", "amo", "tso"].map((f) => (<option key={f} value={f}>{t(`dev.flavour.${f as "sr"}`)}</option>))}
          </select>
        </Field>
        <Field label={t("enr.lockdown")} htmlFor="e-lock" hint={t("enr.lockdown.hint")}>
          <select id="e-lock" value={v.lockdown_level} onChange={(e) => set("lockdown_level", e.target.value)} className={inputClass}>
            <option value="prod">{t("enr.lockdown.prod")}</option>
            <option value="dev">{t("enr.lockdown.dev")}</option>
          </select>
        </Field>
        <Field label={t("enr.zone")} htmlFor="e-zone" hint={t("enr.zone.hint")} error={errors.zone_id}>
          <input id="e-zone" value={v.zone_id} onChange={(e) => set("zone_id", e.target.value)} inputMode="numeric" className={inputClass} />
        </Field>
        <Field label={t("enr.max_uses")} htmlFor="e-uses" hint={t("enr.max_uses.hint")} error={errors.max_uses}>
          <input id="e-uses" value={v.max_uses} onChange={(e) => set("max_uses", e.target.value)} inputMode="numeric" className={inputClass} />
        </Field>
        <Field label={t("enr.expires")} htmlFor="e-exp" hint={t("enr.expires.hint")} error={errors.expires_in_h}>
          <input id="e-exp" value={v.expires_in_h} onChange={(e) => set("expires_in_h", e.target.value)} inputMode="numeric" className={inputClass} />
        </Field>
        <Field label={t("enr.note")} htmlFor="e-note">
          <input id="e-note" value={v.note} onChange={(e) => set("note", e.target.value)} maxLength={300} className={inputClass} />
        </Field>
        <Field label={t("enr.wifi.ssid")} htmlFor="e-ssid" hint={t("enr.wifi.hint")} error={errors.ssid}>
          <input id="e-ssid" value={v.ssid} onChange={(e) => set("ssid", e.target.value)} maxLength={32} autoComplete="off" className={inputClass} />
        </Field>
        <Field label={t("enr.wifi.password")} htmlFor="e-wpw">
          <input id="e-wpw" type="password" value={v.wifi_password} onChange={(e) => set("wifi_password", e.target.value)} maxLength={63} autoComplete="new-password" className={inputClass} />
        </Field>
        <div className="sm:col-span-2">
          <button type="submit" disabled={busy} className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
            {t("enr.create")}
          </button>
        </div>
        {error ? <p role="alert" data-testid="form-error" className="rounded bg-red-50 p-3 text-sm text-red-800 sm:col-span-2">{error}</p> : null}
      </form>
      {created ? (
        <section className="space-y-2 rounded-lg border border-amber-300 bg-amber-50 p-4" data-testid="qr-result">
          <h2 className="text-lg font-semibold">{t("enr.qr.title")}</h2>
          <p className="text-sm">{t("enr.qr.once")}</p>
          <QrCode text={created.qr_text} label={t("enr.qr.title")} />
          <p className="text-xs text-slate-700">{t("enr.qr.steps")}</p>
          <button type="button" onClick={() => setCreated(null)} className="rounded border border-slate-300 bg-white px-3 py-1.5 text-sm">{t("enr.qr.hide")}</button>
        </section>
      ) : null}
    </div>
  );
}
