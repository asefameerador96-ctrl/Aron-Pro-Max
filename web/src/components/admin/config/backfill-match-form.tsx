"use client";
// F-ADM-030b: "same memo as the paper backfill". A held phone memo (memo_no_duplicate) whose stored twin is a manual paper memo is resolved
// as the same memo: the paper memo stays, its held collections are released onto it. Any difference is a 409 with one line per difference.
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import { formatNumber } from "@/lib/i18n";
import { formatMtkExact } from "@/lib/admin/money";
import type { MessageKey } from "@/lib/i18n";
import { Card } from "../kit/page";
import { ReasonField, REASON_MIN_LENGTH } from "../kit/reason-field";

interface Done {
  paper_memo_id: number;
  released: string[];
  kept_open: string[];
}
type Outcome = { kind: "ok"; done: Done } | { kind: "note"; text: string } | { kind: "error"; text: string; diffs: { pointer: string; message: string }[] };

async function post(id: number, reason: string): Promise<Response> {
  return fetch("/api/bff/admin-op", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ op: "quarantine.match-backfill", params: { quarantine_id: String(id) }, reason }) });
}

const FIELDS = ["outlet_id", "gross_mtk", "offer_discount_mtk", "drp_discount_mtk", "qc_deduction_mtk", "round_adj_mtk", "net_mtk", "paid_mtk", "due_mtk", "line_count", "discount_line_count", "qc_line_count"] as const;

export function BackfillMatchForm({ quarantineId, open = true }: { quarantineId: number; open?: boolean }) {
  const { t, problem, number, locale } = useI18n();
  const router = useRouter();
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [out, setOut] = useState<Outcome | null>(null);

  /** One difference as a readable line: field name in the user's language, phone and paper values (money as taka). */
  function diffLine(d: { pointer: string; message: string }): { label: string; detail: string } {
    const key = d.pointer === "/lines" ? "lines" : d.pointer.replace(/^\/payload\//, "");
    const label = key === "lines" ? t("bm.field.lines") : (FIELDS as readonly string[]).includes(key) ? t(`bm.field.${key}` as MessageKey) : d.pointer;
    const m = /^phone (-?\d+), paper (-?\d+)$/.exec(d.message);
    if (!m) return { label, detail: d.message };
    const show = (v: string) => (key.endsWith("_mtk") ? formatMtkExact(locale, Number(v)) : number(Number(v)));
    return { label, detail: t("bm.phone_paper", { phone: show(m[1]!), paper: show(m[2]!) }) };
  }

  async function submit() {
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) return setReasonError(t("admin.reason.too_short"));
    setReasonError(null);
    setBusy(true);
    setOut(null);
    try {
      let res = await post(quarantineId, reason.trim());
      let retried = false;
      if (res.status === 503) {
        retried = true;
        res = await post(quarantineId, reason.trim()); // one retry: the match is all-or-nothing, a repeat of a done match is a plain 409
      }
      const json = (await res.json().catch(() => ({}))) as { data?: Done } & Partial<Problem>;
      if (res.ok && json.data) {
        setOut({ kind: "ok", done: json.data });
        setReason("");
        router.refresh();
      } else if (res.status === 409 && json.code === "ERR_CONFLICT") {
        setOut({ kind: "error", text: t("bm.conflict"), diffs: (json.errors ?? []).map((e) => ({ pointer: typeof e.pointer === "string" ? e.pointer : "", message: typeof e.message === "string" ? e.message : String(e.code ?? "") })) });
      } else if (res.status === 409 && json.code === "ERR_REQUEST_STATE") {
        // After a retried 503 the first attempt may have been committed: say that, not the list of reasons.
        setOut(retried ? { kind: "note", text: t("bm.maybe_done") } : { kind: "error", text: t("bm.state"), diffs: [] });
        router.refresh();
      } else if (res.status === 404) {
        setOut({ kind: "error", text: t("bm.not_found"), diffs: [] });
      } else setOut({ kind: "error", text: problem(json.code), diffs: [] });
    } catch {
      setOut({ kind: "error", text: t("error.network"), diffs: [] });
    } finally {
      setBusy(false);
    }
  }

  if (!open && out === null) return null; // the item is not open any more (or never was): keep only a result already shown
  return (
    <Card title={t("bm.title")} testId="backfill-match">
      <p className="text-sm text-slate-700">{t("bm.intro")}</p>
      <ReasonField value={reason} onChange={setReason} error={reasonError} id="bm-reason" />
      <button type="button" disabled={busy} onClick={() => void submit()} data-testid="bm-submit" className="rounded-full bg-brand-600 px-5 py-2.5 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
        {t("bm.submit")}
      </button>
      {out?.kind === "ok" ? (
        <div role="status" data-testid="bm-ok" className="space-y-1 rounded bg-[color-mix(in_srgb,var(--success)_14%,transparent)] p-3 text-sm text-[var(--success)]">
          <p>{t("bm.done", { id: formatNumber(locale, out.done.paper_memo_id, { useGrouping: false }) })}</p>
          <p data-testid="bm-released">{t("bm.released", { n: number(out.done.released.length) })}</p>
          <p data-testid="bm-kept">{t("bm.kept_open", { n: number(out.done.kept_open.length) })}</p>
        </div>
      ) : null}
      {out?.kind === "note" ? <p role="status" data-testid="bm-note" className="rounded bg-slate-100 p-3 text-sm">{out.text}</p> : null}
      {out?.kind === "error" ? (
        <div role="alert" data-testid="bm-error" className="space-y-2 rounded bg-[color-mix(in_srgb,var(--danger)_12%,transparent)] p-3 text-sm text-[var(--danger)]">
          <p>{out.text}</p>
          {out.diffs.length > 0 ? (
            <ul className="list-disc space-y-1 pl-5" data-testid="bm-diffs">
              {out.diffs.map((d, i) => {
                const l = diffLine(d);
                return (
                  <li key={i}>
                    <span className="font-medium">{l.label}</span>: {l.detail} <span className="font-mono text-xs text-slate-500">{d.pointer}</span>
                  </li>
                );
              })}
            </ul>
          ) : null}
        </div>
      ) : null}
    </Card>
  );
}
