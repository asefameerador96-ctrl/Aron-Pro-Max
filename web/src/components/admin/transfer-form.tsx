"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { callMasterOp } from "./master-op-client";
import { Field, inputClass } from "./kit/field";
import { ReasonField, REASON_MIN_LENGTH } from "./kit/reason-field";

export interface OpenAssignment {
  id: number;
  routeId: number;
  routeLabel: string;
  kind: string;
  validFrom: string;
}

interface Props {
  userId: number;
  open: OpenAssignment[];
  routes: { value: string; label: string }[];
  minDate: string;
}

const REASON_MAX = 300; // route assignment reason maxLength in the contract

/** Moves an SR to another route from a date: a new primary assignment starts, the old primary ends at the same date. */
export function TransferForm({ userId, open, routes, minDate }: Props) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [route, setRoute] = useState("");
  const [date, setDate] = useState(minDate);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [steps, setSteps] = useState<{ ok: boolean; text: string }[]>([]);

  async function submit() {
    const e: Record<string, string> = {};
    const n = Array.from(reason.trim()).length;
    if (n < REASON_MIN_LENGTH) e.reason = t("admin.reason.too_short");
    else if (n > REASON_MAX) e.reason = t("error.field.too_long");
    if (!route) e.route = t("error.field.required");
    if (!date || date < minDate) e.date = t("error.field.past");
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    const out: { ok: boolean; text: string }[] = [];
    // 1. the new primary starts first; if it is refused (overlap, past date) nothing has changed yet.
    const created = await callMasterOp("assignment.create", { body: { route_id: Number(route), user_id: userId, kind: "primary", valid_from: date }, reason: reason.trim() });
    out.push({ ok: created.ok, text: created.ok ? t("transfer.started") : problem(created.problem.code) });
    if (created.ok) {
      // 2. the old primaries end at the same date (exclusive end): history is kept, nothing is re-attributed.
      for (const a of open.filter((x) => x.kind === "primary")) {
        const ended = await callMasterOp("assignment.end", { params: { id: a.id }, body: { valid_to: date }, reason: reason.trim() });
        out.push({ ok: ended.ok, text: ended.ok ? t("transfer.ended", { route: a.routeLabel }) : `${a.routeLabel}: ${problem(ended.problem.code)} ${t("transfer.end_failed")}` });
      }
    }
    setSteps(out);
    setBusy(false);
    if (out.every((s) => s.ok)) {
      setReason("");
      router.refresh();
    }
  }

  return (
    <div className="space-y-4" data-testid="transfer">
      <section className="rounded-lg border border-slate-200 bg-white p-4">
        <h2 className="mb-2 font-semibold">{t("transfer.current")}</h2>
        {open.length === 0 ? (
          <p className="text-slate-600">{t("common.none")}</p>
        ) : (
          <ul className="space-y-1" data-testid="transfer-current">
            {open.map((a) => (
              <li key={a.id}>
                {a.routeLabel} · {t(a.kind === "primary" ? "entity.kind.primary" : "entity.kind.cover")} · {a.validFrom}
              </li>
            ))}
          </ul>
        )}
      </section>
      <Field label={t("transfer.new_route")} htmlFor="f-route" required error={errors.route}>
        <select id="f-route" aria-required="true" value={route} onChange={(ev) => setRoute(ev.target.value)} className={`${inputClass} w-80`}>
          <option value="">{t("common.none")}</option>
          {routes.filter((r) => !open.some((a) => String(a.routeId) === r.value && a.kind === "primary")).map((r) => (
            <option key={r.value} value={r.value}>
              {r.label}
            </option>
          ))}
        </select>
      </Field>
      <Field label={t("transfer.from")} htmlFor="f-date" required error={errors.date}>
        <input id="f-date" type="date" min={minDate} value={date} onChange={(ev) => setDate(ev.target.value)} className={`${inputClass} w-48`} />
      </Field>
      <ReasonField value={reason} onChange={setReason} error={errors.reason} />
      <p className="text-xs text-slate-500">{t("transfer.targets_note")}</p>
      <button type="button" disabled={busy} onClick={submit} data-testid="transfer-submit" className="rounded bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
        {t("transfer.submit")}
      </button>
      <ul className="space-y-1" data-testid="transfer-steps">
        {steps.map((s, i) => (
          <li key={i} role={s.ok ? "status" : "alert"} className={`rounded p-2 text-sm ${s.ok ? "bg-green-50 text-green-800" : "bg-red-50 text-red-800"}`}>
            {s.text}
          </li>
        ))}
      </ul>
    </div>
  );
}
