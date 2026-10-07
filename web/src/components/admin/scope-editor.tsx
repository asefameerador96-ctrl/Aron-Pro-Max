"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { MessageKey } from "@/lib/i18n";
import { callMasterOp } from "./master-op-client";
import { Field, inputClass } from "./kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "./kit/reason-field";

export interface ScopeNodeRow {
  type: string;
  id: number;
  label: string;
  validTo?: string | null;
}

export interface NodeOption {
  value: string;
  label: string;
}

interface Props {
  userId: number;
  /** Node types this user's role may hold (role-node consistency); empty = the role has no scope (SR). */
  allowedTypes: string[];
  current: ScopeNodeRow[];
  options: Record<string, NodeOption[]>;
  minDate: string;
  canWrite: boolean;
}

const TYPE_KEY: Record<string, MessageKey> = { national: "scope.national", wing: "scope.wing", division: "scope.division", territory: "scope.territory", zone: "scope.zone" };

/** Replaces a user's supervisory scope from a date. Only node types the role may hold are offered. */
export function ScopeEditor({ userId, allowedTypes, current, options, minDate, canWrite }: Props) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [nodes, setNodes] = useState<ScopeNodeRow[]>(current);
  const [type, setType] = useState(allowedTypes[0] ?? "");
  const [pick, setPick] = useState("");
  const [validFrom, setValidFrom] = useState(minDate);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [banner, setBanner] = useState<{ ok: boolean; text: string } | null>(null);

  function add() {
    if (!type) return;
    const id = type === "national" ? 0 : Number(pick);
    if (type !== "national" && !pick) return;
    if (nodes.some((n) => n.type === type && n.id === id)) return;
    const label = type === "national" ? t("scope.national") : (options[type]?.find((o) => o.value === pick)?.label ?? pick);
    setNodes([...nodes, { type, id, label }]);
    setPick("");
  }

  async function save() {
    setBanner(null);
    const e: Record<string, string> = {};
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) e.reason = t("admin.reason.too_short");
    if (!validFrom || validFrom < minDate) e.valid_from = t("error.field.past");
    if (nodes.length === 0 && allowedTypes.length > 0) e.nodes = t("error.field.required");
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    const r = await callMasterOp("user-scope.put", { params: { id: userId }, body: { valid_from: validFrom, nodes: nodes.map((n) => ({ node_type: n.type, node_id: n.id })) }, reason: reason.trim() });
    setBusy(false);
    if (r.ok) {
      setBanner({ ok: true, text: t("scope.saved") });
      setReason("");
      router.refresh();
      return;
    }
    setBanner({ ok: false, text: problem(r.problem.code) });
  }

  if (allowedTypes.length === 0) {
    return (
      <p data-testid="no-scope" className="rounded border border-slate-200 bg-white p-4 text-slate-700">
        {t("scope.sr_none")}
      </p>
    );
  }

  return (
    <div className="space-y-4" data-testid="scope-editor">
      <ul className="space-y-2" data-testid="scope-nodes">
        {nodes.length === 0 ? <li className="text-slate-600">{t("scope.none")}</li> : null}
        {nodes.map((n) => (
          <li key={`${n.type}-${n.id}`} className="flex items-center justify-between rounded border border-slate-200 bg-white px-3 py-2">
            <span>
              {t(TYPE_KEY[n.type] ?? "scope.national")} · {n.label}
            </span>
            {canWrite ? (
              <button type="button" aria-label={`${t("common.clear")} ${n.label}`} onClick={() => setNodes(nodes.filter((x) => x !== n))} className="text-sm text-red-700 underline">
                {t("common.clear")}
              </button>
            ) : null}
          </li>
        ))}
      </ul>
      {errors.nodes ? (
        <p role="alert" className="text-xs text-red-700">
          {errors.nodes}
        </p>
      ) : null}
      {canWrite ? (
        <>
          <div className="flex flex-wrap items-end gap-3 rounded-lg border border-slate-200 bg-white p-3">
            <label className="text-sm">
              <span className="mb-1 block">{t("scope.label")}</span>
              <select aria-label={t("scope.label")} value={type} onChange={(e) => { setType(e.target.value); setPick(""); }} className={`${inputClass} w-44`}>
                {allowedTypes.map((a) => (
                  <option key={a} value={a}>
                    {t(TYPE_KEY[a] ?? "scope.national")}
                  </option>
                ))}
              </select>
            </label>
            {type !== "national" ? (
              <label className="text-sm">
                <span className="mb-1 block">{t("scope.node")}</span>
                <select aria-label={t("scope.node")} value={pick} onChange={(e) => setPick(e.target.value)} className={`${inputClass} w-64`}>
                  <option value="">{t("common.none")}</option>
                  {(options[type] ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
            ) : null}
            <button type="button" data-testid="scope-add" onClick={add} className="rounded border border-slate-300 px-3 py-2 text-sm hover:bg-slate-100">
              {t("scope.add")}
            </button>
          </div>
          <Field label={t("entity.field.valid_from")} htmlFor="f-valid_from" required error={errors.valid_from}>
            <input id="f-valid_from" type="date" min={minDate} value={validFrom} onChange={(e) => setValidFrom(e.target.value)} className={`${inputClass} w-48`} />
          </Field>
          <ReasonField value={reason} onChange={setReason} error={errors.reason} />
          {banner ? (
            <p role={banner.ok ? "status" : "alert"} data-testid={banner.ok ? "form-ok" : "form-error"} className={`rounded p-3 text-sm ${banner.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>
              {banner.text}
            </p>
          ) : null}
          <button type="button" disabled={busy} onClick={save} data-testid="scope-save" className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
            {t("common.save")}
          </button>
        </>
      ) : null}
    </div>
  );
}
