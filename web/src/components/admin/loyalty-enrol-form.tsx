"use client";
// Enrol one outlet in a programme (putProgrammeEnrolments, idempotent by batch_uuid): outlet, tier, league label, base target (STD,
// exact decimal, never negative), dates and a mandatory reason. The batch_uuid is kept for a retry of the same body.
import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useI18n } from "@/components/i18n-provider";
import { Banner } from "@/components/kit/banner";
import { Button } from "@/components/kit/button";
import type { Problem } from "@/contract/types";
import type { MessageKey } from "@/lib/i18n";
import { emptyEnrolForm, failureKey, fieldErrorsOf, unplacedText, nextAttempt, validateEnrol, type EnrolForm, type FieldError, type Tier } from "@/lib/admin/loyalty";
import { tierName } from "@/lib/admin/loyalty-view";
import { Field, inputClass } from "./kit/field";
import { ReasonField } from "./kit/reason-field";

export function LoyaltyEnrolForm({ programmeId, tiers }: { programmeId: number; tiers: Tier[] }) {
  const { t, problem, locale } = useI18n();
  const router = useRouter();
  const [f, setF] = useState<EnrolForm>(emptyEnrolForm());
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ tone: "danger" | "success"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const attempt = useRef<{ key: string; uuid: string } | null>(null);
  const set = <K extends keyof EnrolForm>(k: K, v: EnrolForm[K]) => setF((s) => ({ ...s, [k]: v }));

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBanner(null);
    const checked = validateEnrol(f, programmeId, "00000000-0000-4000-8000-000000000000");
    const map: Record<string, string> = {};
    for (const x of checked.errors as FieldError[]) map[x.field] = t(`loy.err.${x.code}` as MessageKey);
    setErrors(map);
    if (!checked.body) return;
    const { batch_uuid: _drop, ...rest } = checked.body;
    void _drop;
    attempt.current = nextAttempt(attempt.current, JSON.stringify(rest));
    setBusy(true);
    try {
      const res = await fetch(`/api/bff/admin/loyalty/${programmeId}/enrolments`, { method: "PUT", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ ...rest, batch_uuid: attempt.current.uuid }) });
      const data = (await res.json().catch(() => ({}))) as Partial<Problem>;
      if (res.ok) {
        attempt.current = null;
        setBanner({ tone: "success", text: t("loy.enrol.done") });
        setF(emptyEnrolForm());
        router.refresh();
        return;
      }
      const errs = data.errors as { pointer: string; code: string }[] | undefined;
      const { byField, rest: unplaced } = fieldErrorsOf(errs, "enrol");
      setErrors(Object.fromEntries(Object.entries(byField).map(([f, c]) => [f, t(`loy.err.${c}` as MessageKey)])));
      const key = failureKey(res.status, data.code, errs, "enrol");
      setBanner({ tone: "danger", text: key ? t(key) : unplaced.length > 0 ? unplacedText(unplaced, (c) => t(`loy.err.${c}` as MessageKey)) : problem(data.code) });
    } catch {
      setBanner({ tone: "danger", text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-4" data-testid="loyalty-enrol-form" aria-busy={busy}>
      <h3 className="text-label text-secondary">{t("loy.enrol.form")}</h3>
      {banner ? <Banner tone={banner.tone} role={banner.tone === "danger" ? "alert" : "status"} testId="loyalty-enrol-banner">{banner.text}</Banner> : null}
      <div className="grid gap-4 md:grid-cols-2">
        <Field label={t("loy.enrol.outlet")} htmlFor="le-outlet" required error={errors.outlet_id}>
          <input id="le-outlet" inputMode="numeric" value={f.outlet_id} onChange={(ev) => set("outlet_id", ev.target.value)} className={inputClass} autoComplete="off" />
        </Field>
        <Field label={t("loy.enrol.tier")} htmlFor="le-tier" error={errors.tier_code}>
          <select id="le-tier" value={f.tier_code} onChange={(ev) => set("tier_code", ev.target.value)} className={inputClass}>
            <option value="">{t("loy.enrol.tier_none")}</option>
            {tiers.map((x) => <option key={x.tier_code} value={x.tier_code}>{tierName(locale, x)}</option>)}
          </select>
        </Field>
        <Field label={t("loy.enrol.league")} htmlFor="le-league" error={errors.league_label}>
          <input id="le-league" value={f.league_label} onChange={(ev) => set("league_label", ev.target.value)} maxLength={40} className={inputClass} />
        </Field>
        <Field label={t("loy.enrol.target")} htmlFor="le-target" hint={t("loy.enrol.target_hint")} error={errors.base_target}>
          <input id="le-target" inputMode="decimal" value={f.base_target} onChange={(ev) => set("base_target", ev.target.value)} className={inputClass} />
        </Field>
        <Field label={t("loy.enrol.from")} htmlFor="le-from" required error={errors.valid_from}>
          <input id="le-from" type="date" value={f.valid_from} onChange={(ev) => set("valid_from", ev.target.value)} className={inputClass} />
        </Field>
        <Field label={t("loy.enrol.to")} htmlFor="le-to" hint={t("loy.enrol.to_hint")} error={errors.valid_to}>
          <input id="le-to" type="date" value={f.valid_to} onChange={(ev) => set("valid_to", ev.target.value)} className={inputClass} />
        </Field>
      </div>
      <ReasonField id="le-reason" value={f.reason} onChange={(v) => set("reason", v)} error={errors.reason} />
      <Button type="submit" aria-disabled={busy || undefined} testId="le-submit">{busy ? t("loy.form.saving") : t("loy.enrol.submit")}</Button>
    </form>
  );
}
