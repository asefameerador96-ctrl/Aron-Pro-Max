"use client";
// Create or edit one gift of the catalogue (createGift / updateGift with If-Match). Super Admin only (Q3 default). points_cost is an
// integer 1..100000 or empty (Astha gifts are chosen, not redeemed); the tiers offered are those of the chosen programme.
import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useI18n } from "@/components/i18n-provider";
import { Banner } from "@/components/kit/banner";
import { Button } from "@/components/kit/button";
import type { Problem } from "@/contract/types";
import type { MessageKey } from "@/lib/i18n";
import { emptyGiftForm, failureKey, fieldErrorsOf, giftUnchanged, nextAttempt, validateGift, type FieldError, type Gift, type GiftForm, type GiftPatch, type Tier } from "@/lib/admin/loyalty";
import { tierName } from "@/lib/admin/loyalty-view";
import { Field, inputClass } from "./kit/field";
import { ReasonField } from "./kit/reason-field";

export interface ProgrammeChoice { id: number; label: string; tiers: Tier[] }

export function LoyaltyGiftForm({ mode, giftId, version, initial, original, programmes, cancelHref }: { mode: "create" | "edit"; giftId?: number; version?: number; initial?: GiftForm; original?: Gift; programmes: ProgrammeChoice[]; cancelHref: string }) {
  const { t, problem, locale } = useI18n();
  const router = useRouter();
  const [f, setF] = useState<GiftForm>(initial ?? emptyGiftForm());
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ tone: "danger" | "success"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const attempt = useRef<{ key: string; uuid: string } | null>(null);
  const set = <K extends keyof GiftForm>(k: K, v: GiftForm[K]) => setF((s) => ({ ...s, [k]: v }));
  const tiers = programmes.find((p) => String(p.id) === f.programme_id)?.tiers ?? [];

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBanner(null);
    const checked = validateGift(f, mode, "00000000-0000-4000-8000-000000000000");
    const map: Record<string, string> = {};
    for (const x of checked.errors as FieldError[]) map[x.field] = t(`loy.err.${x.code}` as MessageKey);
    setErrors(map);
    if (!checked.body) return;
    if (mode === "edit" && original && giftUnchanged(original, checked.body as GiftPatch)) {
      setBanner({ tone: "danger", text: t("loy.err.no_change") });
      return;
    }
    const { request_uuid: _drop, ...rest } = checked.body;
    void _drop;
    attempt.current = nextAttempt(attempt.current, `${mode}:${giftId ?? ""}:${JSON.stringify(rest)}`);
    const payload = mode === "create" ? { ...rest, request_uuid: attempt.current.uuid } : { ...rest, request_uuid: attempt.current.uuid, version };
    setBusy(true);
    try {
      const res = await fetch(mode === "create" ? "/api/bff/admin/loyalty/gifts" : `/api/bff/admin/loyalty/gifts/${giftId}`, { method: mode === "create" ? "POST" : "PATCH", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify(payload) });
      const data = (await res.json().catch(() => ({}))) as Partial<Problem>;
      if (res.ok) {
        attempt.current = null;
        router.push("/admin/loyalty/gifts?saved=1");
        router.refresh();
        return;
      }
      const errs = data.errors as { pointer: string; code: string }[] | undefined;
      const { byField } = fieldErrorsOf(errs, "gift");
      setErrors(Object.fromEntries(Object.entries(byField).map(([f, c]) => [f, t(`loy.err.${c}` as MessageKey)])));
      const key = failureKey(res.status, data.code, errs, "gift");
      setBanner({ tone: "danger", text: key ? t(key) : problem(data.code) });
    } catch {
      setBanner({ tone: "danger", text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} noValidate className="glass-card space-y-4 p-(--card-pad)" data-testid="loyalty-gift-form" aria-busy={busy}>
      <h2 className="text-heading text-primary">{mode === "create" ? t("loy.gifts.form_new") : t("loy.gifts.form_edit", { code: initial?.code ?? "" })}</h2>
      {banner ? <Banner tone={banner.tone} role="alert" testId="loyalty-gift-banner">{banner.text}</Banner> : null}
      <div className="grid gap-4 md:grid-cols-2">
        <Field label={t("loy.gifts.programme")} htmlFor="lg-programme" required error={errors.programme_id}>
          <select id="lg-programme" value={f.programme_id} onChange={(ev) => setF((s) => ({ ...s, programme_id: ev.target.value, tier_codes: [] }))} disabled={mode === "edit"} className={inputClass}>
            <option value="">{t("loy.gifts.pick")}</option>
            {programmes.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
          </select>
        </Field>
        <Field label={t("loy.gifts.code")} htmlFor="lg-code" required hint={t("loy.gifts.code_hint")} error={errors.code}>
          <input id="lg-code" value={f.code} onChange={(ev) => set("code", ev.target.value)} disabled={mode === "edit"} maxLength={41} className={inputClass} autoComplete="off" />
        </Field>
        <Field label={t("loy.gifts.name_en")} htmlFor="lg-name-en" required error={errors.name_en}>
          <input id="lg-name-en" value={f.name_en} onChange={(ev) => set("name_en", ev.target.value)} maxLength={120} className={inputClass} />
        </Field>
        <Field label={t("loy.gifts.name_bn")} htmlFor="lg-name-bn" error={errors.name_bn}>
          <input id="lg-name-bn" value={f.name_bn} onChange={(ev) => set("name_bn", ev.target.value)} maxLength={120} className={inputClass} lang="bn" />
        </Field>
        <Field label={t("loy.gifts.points")} htmlFor="lg-points" hint={t("loy.gifts.points_hint")} error={errors.points}>
          <input id="lg-points" inputMode="numeric" value={f.points} onChange={(ev) => set("points", ev.target.value)} className={inputClass} />
        </Field>
        <Field label={t("loy.gifts.status")} htmlFor="lg-status">
          <select id="lg-status" value={f.status} onChange={(ev) => set("status", ev.target.value as GiftForm["status"])} className={inputClass}>
            <option value="active">{t("loy.gifts.status.active")}</option>
            <option value="inactive">{t("loy.gifts.status.inactive")}</option>
          </select>
        </Field>
      </div>
      <fieldset className="space-y-2">
        <legend className="text-label text-secondary">{t("loy.gifts.tiers")}</legend>
        {tiers.length === 0 ? <p className="text-body text-secondary">{t("loy.gifts.tiers_none")}</p> : (
          <div className="flex flex-wrap gap-x-6 gap-y-2">
            {tiers.map((x) => (
              <label key={x.tier_code} className="flex min-h-11 items-center gap-2 text-body text-primary">
                <input type="checkbox" checked={f.tier_codes.includes(x.tier_code)} onChange={(ev) => set("tier_codes", ev.target.checked ? [...f.tier_codes, x.tier_code] : f.tier_codes.filter((c) => c !== x.tier_code))} data-testid={`lg-tier-${x.tier_code}`} className="size-5" />
                {tierName(locale, x)}
              </label>
            ))}
          </div>
        )}
      </fieldset>
      <p className="text-caption text-secondary">{t("loy.gifts.image_note")}</p>
      <ReasonField id="lg-reason" value={f.reason} onChange={(v) => set("reason", v)} error={errors.reason} />
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" aria-disabled={busy || undefined} testId="lg-submit">{busy ? t("loy.form.saving") : t("loy.gifts.save")}</Button>
        <a href={cancelHref} className="text-body-strong text-accent hover:underline">{t("loy.gifts.cancel")}</a>
      </div>
    </form>
  );
}
