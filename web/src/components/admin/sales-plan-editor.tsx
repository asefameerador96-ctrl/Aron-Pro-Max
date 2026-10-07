"use client";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { callMasterOp } from "./master-op-client";
import { Field, inputClass } from "./kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "./kit/reason-field";

export interface PlanSku {
  id: number;
  code: string;
  name: string;
  category: string;
  categoryLabel: string;
}

interface Props {
  zoneId: number;
  /** Zones of the same territory, for "apply to every zone of the territory" (with this zone). */
  siblingZones: { id: number; label: string }[];
  territoryLabel: string | null;
  skus: PlanSku[];
  selected: number[];
  minDate: string;
  canWrite: boolean;
}

/** The zone's enabled SKUs as a tree (category > SKU) with select-all per group; saved atomically per zone with a reason. */
export function SalesPlanEditor({ zoneId, siblingZones, territoryLabel, skus, selected, minDate, canWrite }: Props) {
  const { t, number, problem } = useI18n();
  const router = useRouter();
  const [ids, setIds] = useState<Set<number>>(new Set(selected));
  const [scope, setScope] = useState<"zone" | "territory">("zone");
  const [validFrom, setValidFrom] = useState(minDate);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [results, setResults] = useState<{ label: string; ok: boolean; text: string }[]>([]);
  const groups = useMemo(() => {
    const m = new Map<string, { label: string; items: PlanSku[] }>();
    for (const s of skus) {
      const g = m.get(s.category) ?? { label: s.categoryLabel, items: [] };
      g.items.push(s);
      m.set(s.category, g);
    }
    return [...m.entries()];
  }, [skus]);

  const toggle = (id: number) => {
    const n = new Set(ids);
    if (n.has(id)) n.delete(id);
    else n.add(id);
    setIds(n);
  };

  async function save() {
    const e: Record<string, string> = {};
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) e.reason = t("admin.reason.too_short");
    if (!validFrom || validFrom < minDate) e.valid_from = t("error.field.past");
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    setResults([]);
    const targets = scope === "territory" ? siblingZones : siblingZones.filter((z) => z.id === zoneId);
    const out: { label: string; ok: boolean; text: string }[] = [];
    // One PUT per zone: each zone is all-or-nothing, a failure in one zone never half-changes another.
    for (const z of targets) {
      const r = await callMasterOp("sales-plan.put", { params: { zone_id: z.id }, body: { valid_from: validFrom, sku_ids: [...ids].sort((a, b) => a - b) }, reason: reason.trim() });
      out.push({ label: z.label, ok: r.ok, text: r.ok ? t("salesplan.zone_saved") : problem(r.problem.code) });
    }
    setResults(out);
    setBusy(false);
    if (out.every((o) => o.ok)) {
      setReason("");
      router.refresh();
    }
  }

  return (
    <div className="space-y-4" data-testid="salesplan">
      <p aria-live="polite" className="text-sm font-semibold" data-testid="plan-count">
        {t("salesplan.count", { n: ids.size })}
      </p>
      {groups.map(([key, g]) => {
        const all = g.items.every((s) => ids.has(s.id));
        return (
          <fieldset key={key} className="rounded-lg border border-slate-200 bg-white p-3" data-testid={`plan-group-${key}`}>
            <legend className="px-1 font-semibold">
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  disabled={!canWrite}
                  checked={all}
                  onChange={(ev) => {
                    const n = new Set(ids);
                    for (const s of g.items) {
                      if (ev.target.checked) n.add(s.id);
                      else n.delete(s.id);
                    }
                    setIds(n);
                  }}
                />
                {g.label} ({number(g.items.length)})
              </label>
            </legend>
            <ul className="grid gap-1 sm:grid-cols-2">
              {g.items.map((s) => (
                <li key={s.id}>
                  <label className="flex items-center gap-2 text-sm">
                    <input type="checkbox" disabled={!canWrite} checked={ids.has(s.id)} onChange={() => toggle(s.id)} />
                    {s.code} · {s.name}
                  </label>
                </li>
              ))}
            </ul>
          </fieldset>
        );
      })}
      {canWrite ? (
        <>
          {territoryLabel && siblingZones.length > 1 ? (
            <fieldset className="space-y-1 text-sm">
              <legend className="font-medium">{t("salesplan.apply_to")}</legend>
              <label className="flex items-center gap-2">
                <input type="radio" name="scope" checked={scope === "zone"} onChange={() => setScope("zone")} />
                {t("salesplan.this_zone")}
              </label>
              <label className="flex items-center gap-2">
                <input type="radio" name="scope" data-testid="scope-territory" checked={scope === "territory"} onChange={() => setScope("territory")} />
                {t("salesplan.all_zones", { n: siblingZones.length, territory: territoryLabel })}
              </label>
            </fieldset>
          ) : null}
          <Field label={t("entity.field.valid_from")} htmlFor="f-valid_from" required error={errors.valid_from}>
            <input id="f-valid_from" type="date" min={minDate} value={validFrom} onChange={(ev) => setValidFrom(ev.target.value)} className={`${inputClass} w-48`} />
          </Field>
          <ReasonField value={reason} onChange={setReason} error={errors.reason} />
          <button type="button" disabled={busy} onClick={save} data-testid="plan-save" className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
            {t("common.save")}
          </button>
          <ul className="space-y-1" data-testid="plan-results">
            {results.map((r) => (
              <li key={r.label} role={r.ok ? "status" : "alert"} className={`rounded p-2 text-sm ${r.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>
                {r.label}: {r.text}
              </li>
            ))}
          </ul>
        </>
      ) : (
        <p className="text-sm text-slate-600">{t("admin.read_only")}</p>
      )}
    </div>
  );
}
