"use client";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { asciiDigits } from "./crud/validation";
import type { MessageKey } from "@/lib/i18n";
import { callMasterOp } from "./master-op-client";
import { Field, inputClass } from "./kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "./kit/reason-field";

/** Proposes cfg.geo.radius_m for one of the TSO's own territories, with a mandatory reason. */
export function RadiusProposal({ territories }: { territories: { id: number; label: string }[] }) {
  const { t, problem } = useI18n();
  const [territory, setTerritory] = useState(String(territories[0]!.id));
  const [value, setValue] = useState("");
  const [reason, setReason] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  async function submit() {
    const e: Record<string, string> = {};
    const v = String(asciiDigits(value)).trim();
    if (!/^[0-9]{2,4}$/.test(v) || Number(v) < 10 || Number(v) > 5000) e.value = t("error.field.invalid");
    const n = Array.from(reason.replace(/[\u200B-\u200D\u2060\uFEFF]/g, "").trim()).length;
    if (n < REASON_MIN_LENGTH) e.reason = t("admin.reason.too_short");
    else if (n > 500) e.reason = t("error.field.too_long");
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    setMsg(null);
    const r = await callMasterOp<{ status?: string }>("radius.propose", { body: { changes: [{ key: "cfg.geo.radius_m", scope_type: "territory", scope_id: Number(territory), value: Number(v) }] }, reason: reason.trim() });
    setBusy(false);
    if (!r.ok) return setMsg({ ok: false, text: problem(r.problem.code) });
    const st = r.data?.status; // never assume an outcome the API did not report
    setMsg({ ok: true, text: st ? t("radius.sent", { status: t(`radius.status.${st}` as MessageKey) }) : t("radius.sent_plain") });
    setValue("");
    setReason("");
  }

  return (
    <div className="space-y-4" data-testid="radius">
      <Field label={t("radius.territory")} htmlFor="f-territory">
        <select id="f-territory" value={territory} onChange={(ev) => setTerritory(ev.target.value)} className={`${inputClass} w-80`}>
          {territories.map((x) => (
            <option key={x.id} value={x.id}>{x.label}</option>
          ))}
        </select>
      </Field>
      <Field label={t("radius.value")} htmlFor="f-radius" required hint={t("radius.hint")} error={errors.value}>
        <input id="f-radius" inputMode="numeric" aria-required="true" value={value} onChange={(ev) => setValue(ev.target.value)} className={`${inputClass} w-40`} />
      </Field>
      <ReasonField value={reason} onChange={setReason} error={errors.reason} />
      <button type="button" disabled={busy} onClick={submit} data-testid="radius-submit" className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">{t("common.save")}</button>
      {msg ? <p role={msg.ok ? "status" : "alert"} data-testid="radius-message" className={`rounded p-2 text-sm ${msg.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>{msg.text}</p> : null}
    </div>
  );
}
