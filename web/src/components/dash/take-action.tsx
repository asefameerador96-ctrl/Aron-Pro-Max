"use client";
import { useState } from "react";

export interface TakeActionLabels {
  open: string;
  note: string;
  send: string;
  sent: string;
  failed: string;
  notified: string;
}

/** Inline "take action" form for one route-day. The action id is generated once per form, so a retry never stores a second note. */
export function TakeAction({ routeId, businessDate, labels }: { routeId: number; businessDate: string; labels: TakeActionLabels }) {
  const [id] = useState(() => crypto.randomUUID());
  const [state, setState] = useState<"idle" | "busy" | "sent" | "failed">("idle");
  const [notified, setNotified] = useState(0);
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const note = String(new FormData(e.currentTarget).get("note") ?? "").trim();
    if (!note) return;
    setState("busy");
    try {
      const res = await fetch("/api/bff/tracking-action", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ action_uuid: id, route_id: routeId, business_date: businessDate, note }) });
      if (!res.ok) return setState("failed");
      const body = (await res.json()) as { notified_user_ids?: number[] };
      setNotified(body.notified_user_ids?.length ?? 0);
      setState("sent");
    } catch {
      setState("failed");
    }
  }
  return (
    <details data-testid={`take-action-${routeId}`}>
      <summary className="cursor-pointer text-sm font-semibold text-brand-700">{labels.open}</summary>
      {state === "sent" ? (
        <p role="status" className="mt-1 text-sm text-emerald-800" data-testid="action-sent">
          {labels.sent} ({labels.notified}: {notified})
        </p>
      ) : (
        <form onSubmit={submit} className="mt-1 flex flex-col gap-1">
          <textarea name="note" required maxLength={500} aria-label={labels.note} className="rounded border border-slate-300 p-1 text-sm" rows={2} />
          <button type="submit" disabled={state === "busy"} className="self-start rounded bg-brand-700 px-3 py-1 text-sm font-semibold text-white disabled:opacity-60">
            {labels.send}
          </button>
          {state === "failed" ? (
            <p role="alert" className="text-sm text-red-800" data-testid="action-failed">
              {labels.failed}
            </p>
          ) : null}
        </form>
      )}
    </details>
  );
}
