"use client";
// tso-manual W1, FS-14 (D-TSO-MAN-29): reopen a final-submitted zone-day through POST /v1/day/reopen (BFF op "day.reopen").
// The server decides the roles (cfg.day.reopen_roles, default ADMIN and SUPERADMIN), the window (cfg.day.reopen_window_days,
// 3) and audits the reopen; this form checks the zone, the date (never a future day) and the reason (10 to 500 characters)
// before sending, asks once for a confirm, and sends `client_uuid` so a retry is the same reopen on the server:
// - the key is kept while an attempt got no answer (network error or 5xx, `keepsKey`) AND the request is unchanged, so a
//   resend after a lost answer replays the first result instead of reopening twice;
// - any answer (2xx or 4xx) settles the key, and a changed zone, date or reason is a new request with a new key, so a lost
//   answer for one zone-day is never replayed as another.
import { useRouter } from "next/navigation";
import { useRef, useState, type FormEvent } from "react";
import { useI18n } from "@/components/i18n-provider";
import { keepsKey } from "@/components/admin/tickets/ticket-rules";
import { latinDigits } from "@/lib/admin/taka";
import type { Schemas } from "@/contract/types";
import { formatBusinessDate, type MessageKey } from "@/lib/i18n";
import { Field, inputClass } from "../kit/field";

export const REOPEN_REASON_MIN = 10;
export const REOPEN_REASON_MAX = 500;

export interface ReopenValues {
  zone: string;
  date: string;
  reason: string;
}
export type ReopenField = "zone_id" | "business_date" | "reason";
export type ReopenProblem = "required" | "invalid" | "future" | "too_short" | "too_long" | "window";

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const realDate = (s: string) => DATE.test(s) && new Date(`${s}T00:00:00Z`).toISOString().slice(0, 10) === s;
const codePoints = (s: string) => Array.from(s.trim()).length;

/** Problems found before sending (code points, like the contract's ChangeReason); an empty object means the request may go. */
export function reopenProblems(v: ReopenValues, today: string): Partial<Record<ReopenField, ReopenProblem>> {
  const out: Partial<Record<ReopenField, ReopenProblem>> = {};
  const zone = latinDigits(v.zone.trim());
  if (zone === "") out.zone_id = "required";
  else if (!/^[1-9]\d{0,14}$/.test(zone)) out.zone_id = "invalid";
  if (v.date === "") out.business_date = "required";
  else if (!realDate(v.date)) out.business_date = "invalid";
  else if (v.date > today) out.business_date = "future";
  const n = codePoints(v.reason);
  if (n === 0) out.reason = "required";
  else if (n < REOPEN_REASON_MIN) out.reason = "too_short";
  else if (n > REOPEN_REASON_MAX) out.reason = "too_long";
  return out;
}

/** The DayReopenRequest of the values (the BFF places the reason; it is part of the body all the same). */
export function reopenBody(v: ReopenValues, clientUuid: string): Schemas["DayReopenRequest"] {
  return { zone_id: Number(latinDigits(v.zone.trim())), business_date: v.date, reason: v.reason.trim(), client_uuid: clientUuid };
}

/** What the last attempt left behind: its key, the request it was for, and whether the server answered it. */
export interface ReopenAttempt {
  key: string;
  signature: string;
  answered: boolean;
}
/** The request a key belongs to: everything but the key. */
export const reopenSignature = (v: ReopenValues): string => JSON.stringify({ ...reopenBody(v, ""), client_uuid: undefined });

/** The key of the next attempt: the last one again only when it got no answer and the request is the same; else a new key. */
export function reopenKey(last: ReopenAttempt | null, signature: string, fresh: () => string): string {
  return last && !last.answered && last.signature === signature ? last.key : fresh();
}

/** The attempt record after an answer (status null = no answer at all). */
export const settle = (key: string, signature: string, status: number | null): ReopenAttempt => ({ key, signature, answered: !keepsKey(status) });

const FIELD_TEXT: Record<ReopenProblem, MessageKey> = {
  required: "error.field.required",
  invalid: "error.field.invalid",
  future: "tsoman.reopen.future",
  too_short: "tsoman.reopen.reason_short",
  too_long: "error.field.too_long",
  window: "tsoman.reopen.window",
};
const SERVER_CODES: readonly string[] = ["required", "invalid", "too_short", "too_long", "window"];

export function DayReopenForm({ zone, date, today, windowDays = 3, testId = "reopen-form" }: { zone: string; date: string; today: string; windowDays?: number; testId?: string }) {
  const { t, problem, locale, dateTime, number } = useI18n();
  const router = useRouter();
  const [values, setValues] = useState<ReopenValues>({ zone, date, reason: "" });
  const [errors, setErrors] = useState<Partial<Record<ReopenField, string>>>({});
  const [banner, setBanner] = useState<{ kind: "ok" | "error"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const last = useRef<ReopenAttempt | null>(null);
  const inFlight = useRef(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (inFlight.current) return; // a second click while the first is on its way does nothing
    setBanner(null);
    const p = reopenProblems(values, today);
    setErrors(Object.fromEntries(Object.entries(p).map(([k, c]) => [k, t(FIELD_TEXT[c])])));
    if (Object.keys(p).length) return;
    const shownDate = formatBusinessDate(locale, values.date);
    const zoneId = latinDigits(values.zone.trim());
    if (!window.confirm(t("tsoman.reopen.confirm", { zone: zoneId, date: shownDate }))) return;
    const signature = reopenSignature(values);
    const key = reopenKey(last.current, signature, () => crypto.randomUUID());
    const { reason, ...body } = reopenBody(values, key);
    inFlight.current = true;
    setBusy(true);
    let status: number | null = null;
    let data: { data?: Partial<Schemas["DayReopenResult"]> | null; code?: string; errors?: { pointer: string; code: string }[] } = {};
    try {
      const res = await fetch("/api/bff/admin-op", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ op: "day.reopen", body, reason }) });
      status = res.status;
      data = (await res.json().catch(() => ({}))) as typeof data;
    } catch {
      status = null;
    }
    last.current = settle(key, signature, status);
    inFlight.current = false;
    setBusy(false);
    if (status !== null && status >= 200 && status < 300) {
      const at = typeof data.data?.reopened_at === "string" ? dateTime(data.data.reopened_at) : "";
      setBanner({ kind: "ok", text: t("tsoman.reopen.done", { zone: zoneId, date: shownDate, at }) });
      setValues((v) => ({ ...v, reason: "" }));
      router.refresh();
      return;
    }
    if (status === null || status >= 500) {
      setBanner({ kind: "error", text: t("tsoman.reopen.retry") });
      return;
    }
    const fieldErrors: Partial<Record<ReopenField, string>> = {};
    for (const err of data.errors ?? []) {
      const name = err.pointer.replace(/^\/?(body[./])?/, "") as ReopenField;
      if (name === "zone_id" || name === "business_date" || name === "reason") fieldErrors[name] = t(FIELD_TEXT[(SERVER_CODES.includes(err.code) ? err.code : "invalid") as ReopenProblem]);
    }
    setErrors(fieldErrors);
    setBanner({ kind: "error", text: problem(data.code) });
  }

  const set = (name: keyof ReopenValues, v: string) => setValues((s) => ({ ...s, [name]: v }));
  const reasonLength = codePoints(values.reason);
  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4 rounded-lg border border-slate-200 bg-white p-4 shadow-sm" data-testid={testId}>
      <p className="text-sm text-slate-600" data-testid="reopen-window">{t("tsoman.reopen.window_hint", { n: windowDays })}</p>
      <Field label={t("cfgc.col.zone")} htmlFor="reopen-zone" required error={errors.zone_id}>
        <input id="reopen-zone" name="zone_id" type="text" inputMode="numeric" value={values.zone} onChange={(e) => set("zone", e.target.value)} maxLength={15} aria-invalid={errors.zone_id ? true : undefined} className={inputClass} />
      </Field>
      <Field label={t("cfgc.col.date")} htmlFor="reopen-date" required error={errors.business_date}>
        <input id="reopen-date" name="business_date" type="date" max={today} value={values.date} onChange={(e) => set("date", e.target.value)} aria-invalid={errors.business_date ? true : undefined} className={inputClass} />
      </Field>
      <Field label={t("admin.reason.label")} htmlFor="reopen-reason" required hint={`${t("tsoman.reopen.reason_hint")} (${number(reasonLength)}/${number(REOPEN_REASON_MAX)})`} error={errors.reason}>
        <textarea id="reopen-reason" name="reason" rows={3} maxLength={REOPEN_REASON_MAX} value={values.reason} onChange={(e) => set("reason", e.target.value)} aria-required="true" aria-invalid={errors.reason ? true : undefined} className={inputClass} />
      </Field>
      {banner ? (
        <p role={banner.kind === "error" ? "alert" : "status"} data-testid={banner.kind === "ok" ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.kind === "ok" ? "bg-[color-mix(in_srgb,var(--success)_14%,transparent)] text-success-on-container" : "bg-[color-mix(in_srgb,var(--danger)_12%,transparent)] text-danger-on-container"}`}>
          {banner.text}
        </p>
      ) : null}
      <button type="submit" disabled={busy} className="rounded-full bg-brand-600 px-5 py-2.5 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50" data-testid="reopen-submit">
        {busy ? t("tickets.busy") : t("day.reopen.submit")}
      </button>
    </form>
  );
}
