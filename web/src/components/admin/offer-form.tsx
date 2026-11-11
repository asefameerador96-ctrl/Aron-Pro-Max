"use client";
// Create-draft and edit-draft form of a discount programme (portal-discount-web, D-DISC-WEB-01). OfferWrite / OfferPatch on the wire:
// money integer milli-taka, quantities base units; the form enters and shows them in the SKU's field unit (packets, boxes, dozens)
// with the base figure alongside. One request_uuid per submission, kept for a retry of the same body.
import { useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useI18n } from "@/components/i18n-provider";
import { Banner } from "@/components/kit/banner";
import { Button } from "@/components/kit/button";
import type { Problem } from "@/contract/types";
import type { MessageKey } from "@/lib/i18n";
import { LEVELS, OFFER_TYPES, PRODUCT_LEVELS, SCOPE_NODE_TYPES, STACKING, newRequestUuid, toPatch, unitCtx, validateForm, type FieldError, type OfferForm, type OfferSkuUnit } from "@/lib/admin/offers";
import { qtyText, unitWord } from "@/lib/admin/offers-view";
import { Field, inputClass } from "./kit/field";
import { ReasonField } from "./kit/reason-field";

export interface Option { value: string; label: string }
export interface SkuOption { id: number; label: string; unit: OfferSkuUnit }
type NodeType = (typeof SCOPE_NODE_TYPES)[number];

const POINTER_FIELD: Record<string, string> = { code: "code", group_code: "group_code", title_en: "title_en", title_bn: "title_bn", valid_from: "valid_from", valid_to: "valid_to", discount_bp: "percent", discount_mtk_per_unit: "perUnit", threshold_qty_base: "threshold", reward_sku_id: "rewardSku", reward_qty_base: "rewardQty", change_reason: "reason", qualifying: "qualifying", scope: "scope" };

export function OfferFormView({ mode, offerId, version, initial, skus, productNodes, nodes, backHref }: { mode: "create" | "edit"; offerId?: number; version?: number; initial: OfferForm; skus: SkuOption[]; productNodes: Record<"category" | "brand" | "variant", Option[]>; nodes: Record<NodeType, Option[]>; backHref: string }) {
  const { t, problem, locale } = useI18n();
  const router = useRouter();
  const [f, setF] = useState<OfferForm>(initial);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ tone: "danger" | "success"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const attempt = useRef<{ key: string; uuid: string } | null>(null);
  const [pLevel, setPLevel] = useState<(typeof PRODUCT_LEVELS)[number]>("sku");
  const [pItem, setPItem] = useState("");
  const [nType, setNType] = useState<NodeType>("wing");
  const [nItem, setNItem] = useState("");
  const set = <K extends keyof OfferForm>(k: K, v: OfferForm[K]) => setF((s) => ({ ...s, [k]: v }));

  const skuById = useMemo(() => new Map(skus.map((s) => [s.id, s])), [skus]);
  const qualUnits = useMemo(() => {
    if (f.qualifying.length === 0 || f.qualifying.some((q) => q.product_level !== "sku")) return null;
    const us = f.qualifying.map((q) => skuById.get(q.product_id)?.unit);
    return us.every(Boolean) ? unitCtx(us as OfferSkuUnit[]) : null;
  }, [f.qualifying, skuById]);
  const rewardUnits = useMemo(() => {
    const s = skuById.get(Number(f.rewardSku));
    return s ? unitCtx([s.unit]) : null;
  }, [f.rewardSku, skuById]);
  const mixed = f.qualifying.length > 0 && qualUnits === null;

  const optionsOf = (level: (typeof PRODUCT_LEVELS)[number]): Option[] => (level === "sku" ? skus.map((s) => ({ value: String(s.id), label: s.label })) : productNodes[level]);
  const labelOfProduct = (level: string, id: number): string => optionsOf(level as (typeof PRODUCT_LEVELS)[number]).find((o) => o.value === String(id))?.label ?? `#${id}`;
  const labelOfNode = (type: string, id: number): string => nodes[type as NodeType]?.find((o) => o.value === String(id))?.label ?? `#${id}`;

  const fieldError = (name: string): string | null => errors[name] ?? null;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBanner(null);
    const { errors: errs, body } = validateForm(f, { qualifying: qualUnits, reward: rewardUnits }, mode);
    const map: Record<string, string> = {};
    for (const x of errs as FieldError[]) map[x.field] = t(`disc.err.${x.code}` as MessageKey);
    if (f.qualifying.length === 0) map.qualifying = t("disc.err.products");
    setErrors(map);
    if (!body || Object.keys(map).length > 0) return;
    const key = `${mode}:${offerId ?? ""}:${JSON.stringify(body)}`;
    if (attempt.current?.key !== key) attempt.current = { key, uuid: newRequestUuid() };
    const uuid = attempt.current.uuid;
    setBusy(true);
    try {
      const url = mode === "create" ? "/api/bff/admin/offers" : `/api/bff/admin/offers/${offerId}`;
      const payload = mode === "create" ? { ...body, request_uuid: uuid } : { ...toPatch(body, uuid), version };
      const res = await fetch(url, { method: mode === "create" ? "POST" : "PATCH", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify(payload) });
      const data = (await res.json().catch(() => ({}))) as { id?: number; version_no?: number } & Partial<Problem>;
      if (res.ok) {
        attempt.current = null;
        router.push(`/admin/discounts/${mode === "create" ? data.id : offerId}?saved=${mode}`);
        router.refresh();
        return;
      }
      const serverErrors: Record<string, string> = {};
      for (const pe of (data.errors ?? []) as { pointer: string }[]) {
        const field = POINTER_FIELD[pe.pointer.replace(/^\//, "").split("/")[0] ?? ""];
        if (field) serverErrors[field] = t("disc.err.invalid");
      }
      setErrors(serverErrors);
      setBanner({ tone: "danger", text: res.status === 409 && mode === "edit" ? t("disc.draft_only") : problem(data.code) });
    } catch {
      setBanner({ tone: "danger", text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  const sel = (id: string, value: string, onChange: (v: string) => void, options: readonly Option[], placeholder = true) => (
    <select id={id} value={value} onChange={(ev) => onChange(ev.target.value)} className={inputClass}>
      {placeholder ? <option value="">{t("disc.form.pick")}</option> : null}
      {options.map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  );

  const addProduct = () => {
    const id = Number(pItem);
    if (!id || f.qualifying.some((q) => q.product_level === pLevel && q.product_id === id)) return;
    set("qualifying", [...f.qualifying, { product_level: pLevel, product_id: id }]);
    setPItem("");
  };
  const addNode = () => {
    const id = Number(nItem);
    if (!id || f.scope.some((s) => s.node_type === nType && s.node_id === id)) return;
    set("scope", [...f.scope, { node_type: nType, node_id: id }]);
    setNItem("");
  };

  const perUnitLabel = qualUnits ? t("disc.form.per_unit", { unit: unitWord(locale, qualUnits) }) : t("disc.form.per_unit_base");
  const thresholdUnit = unitWord(locale, qualUnits);
  const rewardUnit = unitWord(locale, rewardUnits);
  const thresholdBase = qualUnits && /^\d+$/.test(f.threshold.trim()) ? qtyText(locale, Number(f.threshold) * qualUnits.packFactor, qualUnits) : null;
  const rewardBase = rewardUnits && /^\d+$/.test(f.rewardQty.trim()) ? qtyText(locale, Number(f.rewardQty) * rewardUnits.packFactor, rewardUnits) : null;

  return (
    <form onSubmit={submit} noValidate className="space-y-5" data-testid="offer-form" aria-busy={busy}>
      {banner ? <Banner tone={banner.tone} role="alert" testId="offer-form-banner">{banner.text}</Banner> : null}
      <section className="glass-card space-y-4 p-(--card-pad)">
        <div className="grid gap-4 md:grid-cols-2">
          <Field label={t("disc.form.code")} htmlFor="of-code" required hint={t("disc.form.code_hint")} error={fieldError("code")}>
            <input id="of-code" value={f.code} onChange={(ev) => set("code", ev.target.value)} disabled={mode === "edit"} maxLength={40} className={inputClass} autoComplete="off" />
          </Field>
          <Field label={t("disc.form.group")} htmlFor="of-group" hint={t("disc.form.group_hint")} error={fieldError("group_code")}>
            <input id="of-group" value={f.group_code} onChange={(ev) => set("group_code", ev.target.value)} disabled={mode === "edit"} maxLength={40} className={inputClass} autoComplete="off" />
          </Field>
          <Field label={t("disc.form.title_en")} htmlFor="of-title-en" required error={fieldError("title_en")}>
            <input id="of-title-en" value={f.title_en} onChange={(ev) => set("title_en", ev.target.value)} maxLength={200} className={inputClass} />
          </Field>
          <Field label={t("disc.form.title_bn")} htmlFor="of-title-bn" error={fieldError("title_bn")}>
            <input id="of-title-bn" value={f.title_bn} onChange={(ev) => set("title_bn", ev.target.value)} maxLength={200} className={inputClass} lang="bn" />
          </Field>
          <Field label={t("disc.form.type")} htmlFor="of-type">
            {sel("of-type", f.offer_type, (v) => set("offer_type", v as OfferForm["offer_type"]), OFFER_TYPES.map((x) => ({ value: x, label: t(`disc.type.${x}` as MessageKey) })), false)}
          </Field>
          <Field label={t("disc.form.level")} htmlFor="of-level">
            {sel("of-level", f.level, (v) => set("level", v as OfferForm["level"]), LEVELS.map((x) => ({ value: x, label: t(`disc.level.${x}` as MessageKey) })), false)}
          </Field>
          <Field label={t("disc.form.stacking")} htmlFor="of-stacking">
            {sel("of-stacking", f.stacking, (v) => set("stacking", v as OfferForm["stacking"]), STACKING.map((x) => ({ value: x, label: t(`disc.stacking.${x}` as MessageKey) })), false)}
          </Field>
          <span aria-hidden="true" className="hidden md:block" />
          <Field label={t("disc.form.valid_from")} htmlFor="of-from" required error={fieldError("valid_from")}>
            <input id="of-from" type="date" value={f.valid_from} onChange={(ev) => set("valid_from", ev.target.value)} className={inputClass} />
          </Field>
          <Field label={t("disc.form.valid_to")} htmlFor="of-to" required error={fieldError("valid_to")}>
            <input id="of-to" type="date" value={f.valid_to} onChange={(ev) => set("valid_to", ev.target.value)} className={inputClass} />
          </Field>
        </div>
      </section>

      <section className="glass-card space-y-4 p-(--card-pad)" aria-labelledby="of-products-h">
        <div>
          <h2 id="of-products-h" className="text-heading text-primary">{t("disc.form.products")}</h2>
          <p className="text-caption text-secondary">{t("disc.form.products_hint")}</p>
        </div>
        {f.qualifying.length === 0 ? <p className="text-body text-secondary" data-testid="of-products-none">{t("disc.form.products_none")}</p> : (
          <ul className="space-y-1" data-testid="of-products">
            {f.qualifying.map((q) => (
              <li key={`${q.product_level}:${q.product_id}`} className="flex items-center justify-between gap-2 [overflow-wrap:anywhere]">
                <span>{t(`disc.plevel.${q.product_level}` as MessageKey)} · {labelOfProduct(q.product_level, q.product_id)}</span>
                <Button type="button" variant="text" onClick={() => set("qualifying", f.qualifying.filter((x) => x !== q))} aria-label={t("disc.form.remove", { name: labelOfProduct(q.product_level, q.product_id) })}>×</Button>
              </li>
            ))}
          </ul>
        )}
        {errors.qualifying ? <p role="alert" data-testid="error-qualifying" className="text-xs font-medium text-red-700">{errors.qualifying}</p> : null}
        <div className="grid items-end gap-3 md:grid-cols-[12rem_1fr_auto]">
          <Field label={t("disc.form.level_label")} htmlFor="of-plevel">{sel("of-plevel", pLevel, (v) => { setPLevel(v as typeof pLevel); setPItem(""); }, PRODUCT_LEVELS.map((x) => ({ value: x, label: t(`disc.plevel.${x}` as MessageKey) })), false)}</Field>
          <Field label={t("disc.plevel." + pLevel as MessageKey)} htmlFor="of-pitem">{sel("of-pitem", pItem, setPItem, optionsOf(pLevel))}</Field>
          <Button type="button" variant="secondary" onClick={addProduct} testId="of-add-product">{t("disc.form.add")}</Button>
        </div>
        {mixed ? <p role="note" className="text-caption text-secondary" data-testid="of-unit-note">{t("disc.unit_note.mixed")}</p> : null}
      </section>

      <section className="glass-card space-y-4 p-(--card-pad)" aria-labelledby="of-rule-h">
        <h2 id="of-rule-h" className="text-heading text-primary">{t(`disc.type.${f.offer_type}` as MessageKey)}</h2>
        {f.offer_type === "pct_discount" ? (
          <Field label={t("disc.form.percent")} htmlFor="of-percent" required hint={t("disc.form.percent_hint")} error={fieldError("percent")}>
            <input id="of-percent" inputMode="decimal" value={f.percent} onChange={(ev) => set("percent", ev.target.value)} className={inputClass} />
          </Field>
        ) : null}
        {f.offer_type === "amount_per_unit" ? (
          <Field label={perUnitLabel} htmlFor="of-per-unit" required hint={t("disc.form.per_unit_hint")} error={fieldError("perUnit")}>
            <input id="of-per-unit" inputMode="decimal" value={f.perUnit} onChange={(ev) => set("perUnit", ev.target.value)} className={inputClass} />
          </Field>
        ) : null}
        {f.offer_type === "free_qty" ? (
          <div className="grid gap-4 md:grid-cols-2">
            <Field label={t("disc.form.threshold", { unit: thresholdUnit })} htmlFor="of-threshold" required hint={thresholdBase ?? undefined} error={fieldError("threshold")}>
              <input id="of-threshold" inputMode="numeric" value={f.threshold} onChange={(ev) => set("threshold", ev.target.value)} className={inputClass} />
            </Field>
            <span aria-hidden="true" className="hidden md:block" />
            <Field label={t("disc.form.reward_sku")} htmlFor="of-reward-sku" required error={fieldError("rewardSku")}>
              {sel("of-reward-sku", f.rewardSku, (v) => set("rewardSku", v), skus.map((s) => ({ value: String(s.id), label: s.label })))}
            </Field>
            <Field label={t("disc.form.reward_qty", { unit: rewardUnit })} htmlFor="of-reward-qty" required hint={rewardBase ?? undefined} error={fieldError("rewardQty")}>
              <input id="of-reward-qty" inputMode="numeric" value={f.rewardQty} onChange={(ev) => set("rewardQty", ev.target.value)} className={inputClass} />
            </Field>
          </div>
        ) : null}
      </section>

      <section className="glass-card space-y-4 p-(--card-pad)" aria-labelledby="of-scope-h">
        <div>
          <h2 id="of-scope-h" className="text-heading text-primary">{t("disc.form.scope")}</h2>
          <p className="text-caption text-secondary">{t("disc.form.scope_hint")}</p>
        </div>
        {f.scope.length === 0 ? <p className="text-body text-secondary" data-testid="of-scope-none">{t("disc.form.scope_none")}</p> : (
          <ul className="space-y-1" data-testid="of-scope">
            {f.scope.map((s) => (
              <li key={`${s.node_type}:${s.node_id}`} className="flex items-center justify-between gap-2 [overflow-wrap:anywhere]">
                <span>{t(`disc.node.${s.node_type}` as MessageKey)} · {labelOfNode(s.node_type, s.node_id)}</span>
                <Button type="button" variant="text" onClick={() => set("scope", f.scope.filter((x) => x !== s))} aria-label={t("disc.form.remove", { name: labelOfNode(s.node_type, s.node_id) })}>×</Button>
              </li>
            ))}
          </ul>
        )}
        <div className="grid items-end gap-3 md:grid-cols-[12rem_1fr_auto]">
          <Field label={t("disc.form.node_type")} htmlFor="of-ntype">{sel("of-ntype", nType, (v) => { setNType(v as NodeType); setNItem(""); }, SCOPE_NODE_TYPES.map((x) => ({ value: x, label: t(`disc.node.${x}` as MessageKey) })), false)}</Field>
          <Field label={t("disc.form.node")} htmlFor="of-nitem">{sel("of-nitem", nItem, setNItem, nodes[nType] ?? [])}</Field>
          <Button type="button" variant="secondary" onClick={addNode} testId="of-add-node">{t("disc.form.add")}</Button>
        </div>
      </section>

      <section className="glass-card space-y-4 p-(--card-pad)">
        <ReasonField id="of-reason" value={f.reason} onChange={(v) => set("reason", v)} error={fieldError("reason")} />
      </section>

      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" aria-disabled={busy || undefined} testId="of-submit">{busy ? t("disc.saving") : mode === "create" ? t("disc.save_draft") : t("disc.save_changes")}</Button>
        <Link href={backHref} className="text-body-strong text-accent hover:underline">{t("disc.back_detail")}</Link>
      </div>
    </form>
  );
}
