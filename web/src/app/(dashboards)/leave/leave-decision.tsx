"use client";
// tso-manual W1, LV-19: the DMO's decision on one leave application. Approve is one tap; Reject opens an optional note for the
// TSO (DecisionRequest.note, at most 300 characters through the BFF) and asks for a second tap. The server checks the role and
// the TSO's reach (/api/bff/leave-decision); a refusal shows its text and the row stays as it was.
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { decisionBody, NOTE_MAX, noteTooLong } from "./leave-rows";

export function LeaveDecision({ leaveUuid }: { leaveUuid: string }) {
  const { t, problem, number } = useI18n();
  const router = useRouter();
  const [rejecting, setRejecting] = useState(false);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function decide(decision: "approve" | "reject") {
    if (busy) return;
    if (decision === "reject" && noteTooLong(note)) {
      setError(t("error.field.too_long"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const res = await fetch("/api/bff/leave-decision", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(decisionBody(leaveUuid, decision, note)) });
      if (res.ok) {
        setRejecting(false);
        setNote("");
        router.refresh();
      } else {
        const data = (await res.json().catch(() => ({}))) as { code?: string };
        setError(problem(data.code));
      }
    } catch {
      setError(t("error.network"));
    } finally {
      setBusy(false);
    }
  }

  const btn = "rounded px-3 py-1 text-sm font-semibold disabled:opacity-60";
  return (
    <span className="inline-flex flex-col gap-1" data-testid={`decision-${leaveUuid}`}>
      {!rejecting ? (
        <span className="flex gap-1">
          <button type="button" onClick={() => decide("approve")} disabled={busy} data-testid={`approve-${leaveUuid}`} className={`${btn} bg-emerald-700 text-white`}>
            {t("leave.approve")}
          </button>
          <button type="button" onClick={() => setRejecting(true)} disabled={busy} data-testid={`reject-${leaveUuid}`} className={`${btn} bg-red-700 text-white`}>
            {t("leave.reject")}
          </button>
        </span>
      ) : (
        <span className="flex flex-col gap-1">
          <label htmlFor={`note-${leaveUuid}`} className="text-xs font-medium text-slate-700">
            {t("tsoman.leave.note")}
          </label>
          <textarea id={`note-${leaveUuid}`} rows={2} maxLength={NOTE_MAX} value={note} onChange={(e) => setNote(e.target.value)} className="min-w-48 rounded border border-slate-300 px-2 py-1 text-sm" data-testid={`note-${leaveUuid}`} />
          <span className="text-xs text-slate-500">{t("tsoman.leave.note_hint", { n: number(NOTE_MAX) })}</span>
          <span className="flex gap-1">
            <button type="button" onClick={() => decide("reject")} disabled={busy} data-testid={`reject-confirm-${leaveUuid}`} className={`${btn} bg-red-700 text-white`}>
              {t("tsoman.leave.reject_confirm")}
            </button>
            <button type="button" onClick={() => { setRejecting(false); setError(null); }} disabled={busy} className={`${btn} border border-slate-300 bg-white`}>
              {t("tsoman.leave.back")}
            </button>
          </span>
        </span>
      )}
      {error ? (
        <span role="alert" className="text-xs text-red-800" data-testid="action-error">
          {error}
        </span>
      ) : null}
    </span>
  );
}
