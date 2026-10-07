"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";

/** One button that POSTs a small JSON body to a BFF route and refreshes the page. A client-generated id in the body makes a retry harmless. */
export function JsonAction({ url, body, text, failed, tone = "neutral", testId }: { url: string; body: Record<string, unknown>; text: string; failed: string; tone?: "neutral" | "good" | "bad"; testId?: string }) {
  const router = useRouter();
  const [id] = useState(() => crypto.randomUUID());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);
  const color = tone === "good" ? "bg-emerald-700 text-white" : tone === "bad" ? "bg-red-700 text-white" : "border border-slate-300 bg-white";
  async function go() {
    setBusy(true);
    setError(false);
    try {
      const res = await fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ ...body, request_uuid: id }) });
      if (!res.ok) setError(true);
      else router.refresh();
    } catch {
      setError(true);
    } finally {
      setBusy(false);
    }
  }
  return (
    <span className="inline-flex flex-col">
      <button type="button" onClick={go} disabled={busy} data-testid={testId} className={`rounded px-3 py-1 text-sm font-semibold disabled:opacity-60 ${color}`}>
        {text}
      </button>
      {error ? (
        <span role="alert" className="text-xs text-red-800" data-testid="action-error">
          {failed}
        </span>
      ) : null}
    </span>
  );
}
