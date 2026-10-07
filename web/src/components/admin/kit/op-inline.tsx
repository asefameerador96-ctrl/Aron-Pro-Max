"use client";
// A row action: a button that opens a small reason box and runs one whitelisted operation (lib/admin/ops.ts).
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import type { OpKey } from "@/lib/admin/ops";
import type { MessageKey } from "@/lib/i18n";
import { REASON_MIN_LENGTH } from "./reason-field";

interface Props {
  op: OpKey;
  params?: Record<string, string>;
  body?: Record<string, unknown>;
  /** If-Match version of the row. */
  version?: number;
  label: string;
  /** Operation without a reason (the whitelist must agree). */
  noReason?: boolean;
  successKey?: MessageKey;
  /** Show this member of the response after success (the issued OTP, for example). */
  resultField?: string;
  /** Style as a destructive action. */
  danger?: boolean;
  testId?: string;
}

export function OpInline({ op, params, body, version, label, noReason, successKey, resultField, danger, testId = "op-inline" }: Props) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  async function run() {
    if (!noReason && Array.from(reason.trim()).length < REASON_MIN_LENGTH) {
      setMsg({ kind: "error", text: t("admin.reason.too_short") });
      return;
    }
    setBusy(true);
    setMsg(null);
    try {
      const res = await fetch("/api/bff/admin-op", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({ op, params, body, ...(noReason ? {} : { reason: reason.trim() }), ...(version !== undefined ? { version } : {}) }),
      });
      const data = (await res.json().catch(() => ({}))) as { data?: Record<string, unknown> | null } & Partial<Problem>;
      if (res.ok) {
        const shown = resultField && data.data ? String(data.data[resultField] ?? "") : "";
        setMsg({ kind: "ok", text: `${t(successKey ?? "admin.save.ok")}${shown ? ` ${shown}` : ""}` });
        setOpen(false);
        setReason("");
        router.refresh();
      } else setMsg({ kind: "error", text: problem(data.code) });
    } catch {
      setMsg({ kind: "error", text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  const btn = danger ? "border-red-300 text-red-700 hover:bg-red-50" : "border-slate-300 text-slate-800 hover:bg-slate-100";
  return (
    <div className="space-y-1" data-testid={testId}>
      {open ? (
        <div className="space-y-1">
          {noReason ? null : <textarea aria-label={t("admin.reason.label")} value={reason} onChange={(e) => setReason(e.target.value)} rows={2} maxLength={500} className="w-56 rounded border border-slate-300 px-2 py-1 text-sm" placeholder={t("admin.reason.hint")} />}
          <div className="flex gap-2">
            <button type="button" disabled={busy} onClick={run} className="rounded bg-brand-600 px-2 py-1 text-xs font-semibold text-white hover:bg-brand-700 disabled:opacity-50">
              {t("cfgc.confirm")}
            </button>
            <button type="button" onClick={() => setOpen(false)} className="rounded border border-slate-300 px-2 py-1 text-xs">
              {t("common.cancel")}
            </button>
          </div>
        </div>
      ) : (
        <button type="button" onClick={() => setOpen(true)} className={`rounded border px-2 py-1 text-xs ${btn}`}>
          {label}
        </button>
      )}
      {msg ? (
        <p role={msg.kind === "error" ? "alert" : "status"} data-testid={msg.kind === "ok" ? "form-ok" : "form-error"} className={`text-xs ${msg.kind === "ok" ? "text-green-700" : "text-red-700"}`}>
          {msg.text}
        </p>
      ) : null}
    </div>
  );
}
