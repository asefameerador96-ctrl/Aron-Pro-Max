"use client";
// F-ADM-030b: "same memo as the paper backfill". A held phone memo (memo_no_duplicate) whose stored twin is a manual paper memo is resolved
// as the same memo: the paper memo stays, its held collections are released onto it. Any difference is a 409 with one line per difference.
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import { Card } from "../kit/page";
import { ReasonField, REASON_MIN_LENGTH } from "../kit/reason-field";

interface Done {
  paper_memo_id: number;
  released: string[];
  kept_open: string[];
}
type Outcome = { kind: "ok"; done: Done } | { kind: "error"; text: string; diffs: { pointer: string; message: string }[] };

async function post(id: number, reason: string): Promise<Response> {
  return fetch("/api/bff/admin-op", { method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin", body: JSON.stringify({ op: "quarantine.match-backfill", params: { quarantine_id: String(id) }, reason }) });
}

export function BackfillMatchForm({ quarantineId }: { quarantineId: number }) {
  const { t, problem, number } = useI18n();
  const router = useRouter();
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [out, setOut] = useState<Outcome | null>(null);

  async function submit() {
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) return setReasonError(t("admin.reason.too_short"));
    setReasonError(null);
    setBusy(true);
    setOut(null);
    try {
      let res = await post(quarantineId, reason.trim());
      if (res.status === 503) res = await post(quarantineId, reason.trim()); // one retry: the match is all-or-nothing, a repeat of a done match is a plain 409
      const json = (await res.json().catch(() => ({}))) as { data?: Done } & Partial<Problem>;
      if (res.ok && json.data) {
        setOut({ kind: "ok", done: json.data });
        setReason("");
        router.refresh();
      } else if (res.status === 409 && json.code === "ERR_CONFLICT") {
        setOut({ kind: "error", text: t("bm.conflict"), diffs: (json.errors ?? []).map((e) => ({ pointer: e.pointer, message: e.message ?? e.code })) });
      } else if (res.status === 409 && json.code === "ERR_REQUEST_STATE") {
        setOut({ kind: "error", text: t("bm.state"), diffs: [] });
      } else if (res.status === 404) {
        setOut({ kind: "error", text: t("bm.not_found"), diffs: [] });
      } else setOut({ kind: "error", text: problem(json.code), diffs: [] });
    } catch {
      setOut({ kind: "error", text: t("error.network"), diffs: [] });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title={t("bm.title")} testId="backfill-match">
      <p className="text-sm text-slate-700">{t("bm.intro")}</p>
      <ReasonField value={reason} onChange={setReason} error={reasonError} id="bm-reason" />
      <button type="button" disabled={busy} onClick={() => void submit()} data-testid="bm-submit" className="rounded-full bg-brand-600 px-5 py-2.5 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
        {t("bm.submit")}
      </button>
      {out?.kind === "ok" ? (
        <div role="status" data-testid="bm-ok" className="space-y-1 rounded bg-[color-mix(in_srgb,var(--success)_14%,transparent)] p-3 text-sm text-[var(--success)]">
          <p>{t("bm.done", { id: number(out.done.paper_memo_id) })}</p>
          <p data-testid="bm-released">{t("bm.released", { n: number(out.done.released.length) })}</p>
          <p data-testid="bm-kept">{t("bm.kept_open", { n: number(out.done.kept_open.length) })}</p>
        </div>
      ) : null}
      {out?.kind === "error" ? (
        <div role="alert" data-testid="bm-error" className="space-y-2 rounded bg-[color-mix(in_srgb,var(--danger)_12%,transparent)] p-3 text-sm text-[var(--danger)]">
          <p>{out.text}</p>
          {out.diffs.length > 0 ? (
            <ul className="list-disc space-y-1 pl-5" data-testid="bm-diffs">
              {out.diffs.map((d, i) => (
                <li key={i}>
                  <span className="font-mono text-xs">{d.pointer}</span> {d.message}
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}
    </Card>
  );
}
