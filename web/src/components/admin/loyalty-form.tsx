"use client";
// Create-draft and edit-draft form of a loyalty programme (portal-loyalty-web, D-LOY-WEB-01): kind, code, names, period, dates, tier
// editor (STD thresholds as stored, rising by tier), outlet channels and the mandatory reason. One request_uuid per submission, kept
// for a retry of the same body. The browser sends no scope: the API derives it from the token.
import { useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useI18n } from "@/components/i18n-provider";
import { Banner } from "@/components/kit/banner";
import { Button } from "@/components/kit/button";
import type { Problem } from "@/contract/types";
import type { MessageKey } from "@/lib/i18n";
import { PROGRAMME_KINDS, emptyTier, nextAttempt, validateForm, type FieldError, type LoyaltyForm } from "@/lib/admin/loyalty";
import { Field, inputClass } from "./kit/field";
import { ReasonField } from "./kit/reason-field";

const POINTER_FIELD: Record<string, string> = { code: "code", name_en: "name_en", name_bn: "name_bn", period_label: "period_label", active_from: "active_from", active_to: "active_to", change_reason: "reason", kind: "kind" };

export function LoyaltyFormView({ mode, programmeId, version, initial, backHref }: { mode: "create" | "edit"; programmeId?: number; version?: number; initial: LoyaltyForm; backHref: string }) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [f, setF] = useState<LoyaltyForm>(initial);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ tone: "danger" | "success"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const attempt = useRef<{ key: string; uuid: string } | null>(null);
  const set = <K extends keyof LoyaltyForm>(k: K, v: LoyaltyForm[K]) => setF((s) => ({ ...s, [k]: v }));
  const setTier = (i: number, patch: Partial<LoyaltyForm["tiers"][number]>) => setF((s) => ({ ...s, tiers: s.tiers.map((x, j) => (j === i ? { ...x, ...patch } : x)) }));
  const err = (name: string): string | null => errors[name] ?? null;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBanner(null);
    const checked = validateForm(f, mode, "00000000-0000-4000-8000-000000000000");
    const map: Record<string, string> = {};
    for (const x of checked.errors as FieldError[]) map[x.field] = t(`loy.err.${x.code}` as MessageKey);
    setErrors(map);
    if (!checked.body || Object.keys(map).length > 0) {
      if (Object.keys(map).length > 0) setBanner({ tone: "danger", text: t("loy.form.errors_summary") });
      return;
    }
    const { request_uuid: _drop, ...rest } = checked.body;
    void _drop;
    attempt.current = nextAttempt(attempt.current, `${mode}:${programmeId ?? ""}:${JSON.stringify(rest)}`);
    const payload = mode === "create" ? { ...rest, request_uuid: attempt.current.uuid } : { ...rest, request_uuid: attempt.current.uuid, version };
    setBusy(true);
    try {
      const res = await fetch(mode === "create" ? "/api/bff/admin/loyalty" : `/api/bff/admin/loyalty/${programmeId}`, { method: mode === "create" ? "POST" : "PATCH", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify(payload) });
      const data = (await res.json().catch(() => ({}))) as { programme_id?: number } & Partial<Problem>;
      if (res.ok) {
        attempt.current = null;
        router.push(`/admin/loyalty/${mode === "create" ? data.programme_id : programmeId}?saved=${mode}`);
        router.refresh();
        return;
      }
      const serverErrors: Record<string, string> = {};
      for (const pe of (data.errors ?? []) as { pointer: string }[]) {
        const field = POINTER_FIELD[pe.pointer.replace(/^\//, "").split("/")[0] ?? ""];
        if (field) serverErrors[field] = t("loy.err.invalid");
      }
      setErrors(serverErrors);
      setBanner({ tone: "danger", text: res.status === 409 && mode === "edit" ? t("loy.draft_only") : problem(data.code) });
    } catch {
      setBanner({ tone: "danger", text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-5" data-testid="loyalty-form" aria-busy={busy}>
      {banner ? <Banner tone={banner.tone} role="alert" testId="loyalty-form-banner">{banner.text}</Banner> : null}
      <section className="glass-card space-y-4 p-(--card-pad)">
        <div className="grid gap-4 md:grid-cols-2">
          <Field label={t("loy.form.kind")} htmlFor="lf-kind">
            <select id="lf-kind" value={f.kind} onChange={(ev) => set("kind", ev.target.value as LoyaltyForm["kind"])} disabled={mode === "edit"} className={inputClass}>
              {PROGRAMME_KINDS.map((k) => <option key={k} value={k}>{t(`loy.kind.${k}` as MessageKey)}</option>)}
            </select>
          </Field>
          <Field label={t("loy.form.code")} htmlFor="lf-code" required hint={t("loy.form.code_hint")} error={err("code")}>
            <input id="lf-code" value={f.code} onChange={(ev) => set("code", ev.target.value)} disabled={mode === "edit"} maxLength={40} className={inputClass} autoComplete="off" />
          </Field>
          <Field label={t("loy.form.name_en")} htmlFor="lf-name-en" required error={err("name_en")}>
            <input id="lf-name-en" value={f.name_en} onChange={(ev) => set("name_en", ev.target.value)} maxLength={120} className={inputClass} />
          </Field>
          <Field label={t("loy.form.name_bn")} htmlFor="lf-name-bn" error={err("name_bn")}>
            <input id="lf-name-bn" value={f.name_bn} onChange={(ev) => set("name_bn", ev.target.value)} maxLength={120} className={inputClass} lang="bn" />
          </Field>
          <Field label={t("loy.form.period")} htmlFor="lf-period" hint={t("loy.form.period_hint")} error={err("period_label")}>
            <input id="lf-period" value={f.period_label} onChange={(ev) => set("period_label", ev.target.value)} maxLength={40} className={inputClass} />
          </Field>
          <span aria-hidden="true" className="hidden md:block" />
          <Field label={t("loy.form.active_from")} htmlFor="lf-from" required error={err("active_from")}>
            <input id="lf-from" type="date" value={f.active_from} onChange={(ev) => set("active_from", ev.target.value)} className={inputClass} />
          </Field>
          <Field label={t("loy.form.active_to")} htmlFor="lf-to" required error={err("active_to")}>
            <input id="lf-to" type="date" value={f.active_to} onChange={(ev) => set("active_to", ev.target.value)} className={inputClass} />
          </Field>
        </div>
      </section>

      <section className="glass-card space-y-4 p-(--card-pad)" aria-labelledby="lf-tiers-h">
        <div>
          <h2 id="lf-tiers-h" className="text-heading text-primary">{t("loy.form.tiers")}</h2>
          <p className="text-caption text-secondary">{t("loy.form.tiers_hint")}</p>
        </div>
        {err("tiers") ? <p role="alert" data-testid="error-tiers" className="text-xs font-medium text-red-700">{err("tiers")}</p> : null}
        {f.tiers.length === 0 ? <p className="text-body text-secondary" data-testid="lf-tiers-none">{t("loy.form.tiers_none")}</p> : (
          <ol className="space-y-4" data-testid="lf-tiers">
            {f.tiers.map((tier, i) => (
              <li key={i} className="space-y-3 rounded-card border border-divider p-3" data-testid={`lf-tier-${i}`}>
                <div className="flex items-center justify-between gap-2">
                  <p className="text-body-strong text-primary">{t("loy.form.tier_n", { n: i + 1 })}</p>
                  <Button type="button" variant="text" onClick={() => set("tiers", f.tiers.filter((_, j) => j !== i))} aria-label={t("loy.form.remove_tier", { n: i + 1 })}>×</Button>
                </div>
                <div className="grid gap-3 md:grid-cols-2">
                  <Field label={t("loy.form.tier_code")} htmlFor={`lf-tier-${i}-code`} required error={err(`tier.${i}.tier_code`)}>
                    <input id={`lf-tier-${i}-code`} value={tier.tier_code} onChange={(ev) => setTier(i, { tier_code: ev.target.value })} maxLength={41} className={inputClass} autoComplete="off" />
                  </Field>
                  <Field label={t("loy.form.threshold")} htmlFor={`lf-tier-${i}-threshold`} hint={t("loy.unit.std")} error={err(`tier.${i}.threshold`)}>
                    <input id={`lf-tier-${i}-threshold`} inputMode="decimal" value={tier.threshold} onChange={(ev) => setTier(i, { threshold: ev.target.value })} className={inputClass} />
                  </Field>
                  <Field label={t("loy.form.tier_label_en")} htmlFor={`lf-tier-${i}-en`} required error={err(`tier.${i}.label_en`)}>
                    <input id={`lf-tier-${i}-en`} value={tier.label_en} onChange={(ev) => setTier(i, { label_en: ev.target.value })} maxLength={80} className={inputClass} />
                  </Field>
                  <Field label={t("loy.form.tier_label_bn")} htmlFor={`lf-tier-${i}-bn`} error={err(`tier.${i}.label_bn`)}>
                    <input id={`lf-tier-${i}-bn`} value={tier.label_bn} onChange={(ev) => setTier(i, { label_bn: ev.target.value })} maxLength={80} className={inputClass} lang="bn" />
                  </Field>
                </div>
              </li>
            ))}
          </ol>
        )}
        <Button type="button" variant="secondary" onClick={() => set("tiers", [...f.tiers, emptyTier()])} testId="lf-add-tier">{t("loy.form.add_tier")}</Button>
      </section>

      <section className="glass-card space-y-4 p-(--card-pad)">
        <ReasonField id="lf-reason" value={f.reason} onChange={(v) => set("reason", v)} error={err("reason")} />
      </section>

      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" aria-disabled={busy || undefined} testId="lf-submit">{busy ? t("loy.form.saving") : mode === "create" ? t("loy.form.save_draft") : t("loy.form.save_changes")}</Button>
        <Link href={backHref} className="text-body-strong text-accent hover:underline">{t("loy.back_detail")}</Link>
      </div>
    </form>
  );
}
