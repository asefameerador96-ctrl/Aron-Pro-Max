"use client";
// D-MDM-01 actions on one device: lock, unlock, message, locate, reboot, factory reset. Each needs a reason (audited).
// The command_id is generated here and kept until the command is accepted, so a double click or a retry after a lost answer
// is the same command on the server (idempotent create); a factory reset waits for a second approver.
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Problem } from "@/contract/types";
import { MDM_COMMANDS, type MdmCommandType } from "@/lib/admin/mdm";
import type { MessageKey } from "@/lib/i18n";
import { Field, inputClass } from "../kit/field";
import { REASON_MIN_LENGTH } from "../kit/reason-field";

type CommandKind = (typeof MDM_COMMANDS)[number];

/** The `params` member of a command (message text is kept per language; at least one is required). */
export function commandParams(type: CommandKind, text: { bn: string; en: string }): Record<string, string> | undefined {
  if (type === "message") {
    const p: Record<string, string> = {};
    if (text.bn.trim()) p.text_bn = text.bn.trim();
    if (text.en.trim()) p.text_en = text.en.trim();
    return p;
  }
  if (type === "lock" && (text.bn.trim() || text.en.trim())) return { message: (text.bn.trim() || text.en.trim()).slice(0, 300) };
  return undefined;
}

export function CommandPanel({ deviceId }: { deviceId: number }) {
  const { t, problem } = useI18n();
  const router = useRouter();
  const [open, setOpen] = useState<CommandKind | null>(null);
  const [reason, setReason] = useState("");
  const [text, setText] = useState({ bn: "", en: "" });
  const ids = useRef<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ kind: "ok" | "error"; text: string } | null>(null);
  const [err, setErr] = useState<Record<string, string>>({});

  const choose = (c: CommandKind) => {
    setOpen(c);
    setMsg(null);
    setErr({});
  };

  async function run(c: CommandKind) {
    const local: Record<string, string> = {};
    if (Array.from(reason.trim()).length < REASON_MIN_LENGTH) local.reason = t("admin.reason.too_short");
    if (c === "message" && !text.bn.trim() && !text.en.trim()) local.text = t("error.field.required");
    setErr(local);
    if (Object.keys(local).length) return;
    setBusy(true);
    setMsg(null);
    try {
      const params = commandParams(c, text);
      const res = await fetch("/api/bff/admin-op", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({ op: "mdm.command", params: { device_id: String(deviceId) }, body: { command_id: (ids.current[c] ??= crypto.randomUUID()), type: c satisfies MdmCommandType, ...(params ? { params } : {}) }, reason: reason.trim() }),
      });
      const data = (await res.json().catch(() => ({}))) as { data?: { state?: string } | null } & Partial<Problem>;
      if (res.ok) {
        setMsg({ kind: "ok", text: t(data.data?.state === "pending_approval" ? "mdm.cmd.pending_approval" : "mdm.cmd.queued") });
        delete ids.current[c];
        setOpen(null);
        setReason("");
        setText({ bn: "", en: "" });
        router.refresh();
      } else setMsg({ kind: "error", text: problem(data.code) });
    } catch {
      setMsg({ kind: "error", text: t("error.network") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-3" data-testid="mdm-actions">
      <div className="flex flex-wrap gap-2">
        {MDM_COMMANDS.map((c) => (
          <button
            key={c}
            type="button"
            data-testid={`mdm-cmd-${c}`}
            aria-pressed={open === c}
            onClick={() => choose(c)}
            className={`min-h-9 rounded border px-3 py-1.5 text-sm ${c === "factory_reset" ? "border-[var(--danger)] text-[var(--danger)]" : "border-slate-300 text-slate-800"} ${open === c ? "bg-slate-100" : "hover:bg-slate-100"}`}
          >
            {t(`mdm.cmd.${c}` as MessageKey)}
          </button>
        ))}
      </div>
      {open ? (
        <div className="space-y-3 rounded-lg border border-slate-200 bg-white p-3" data-testid="mdm-cmd-form">
          <p className="text-sm text-slate-700">{t(`mdm.cmd.${open}.hint` as MessageKey)}</p>
          {open === "factory_reset" ? (
            <p role="note" data-testid="mdm-second-approver" className="rounded bg-[color-mix(in_srgb,var(--danger)_12%,transparent)] p-2 text-sm text-[var(--danger)]">
              {t("mdm.cmd.factory_reset.second")}
            </p>
          ) : null}
          {open === "message" || open === "lock" ? (
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label={t("mdm.cmd.text_bn")} htmlFor="mdm-text-bn" error={err.text}>
                <textarea id="mdm-text-bn" rows={2} maxLength={300} value={text.bn} onChange={(e) => setText((s) => ({ ...s, bn: e.target.value }))} className={inputClass} />
              </Field>
              <Field label={t("mdm.cmd.text_en")} htmlFor="mdm-text-en">
                <textarea id="mdm-text-en" rows={2} maxLength={300} value={text.en} onChange={(e) => setText((s) => ({ ...s, en: e.target.value }))} className={inputClass} />
              </Field>
            </div>
          ) : null}
          <Field label={t("admin.reason.label")} htmlFor="mdm-reason" required hint={t("admin.reason.hint")} error={err.reason}>
            <textarea id="mdm-reason" rows={2} maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} className={inputClass} />
          </Field>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={busy}
              data-testid="mdm-cmd-confirm"
              onClick={() => {
                if (open === "factory_reset" && !window.confirm(t("mdm.cmd.factory_reset.confirm"))) return;
                void run(open);
              }}
              className="rounded-full bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50"
            >
              {t("cfgc.confirm")}
            </button>
            <button type="button" onClick={() => setOpen(null)} className="rounded border border-slate-300 px-3 py-2 text-sm">
              {t("common.cancel")}
            </button>
          </div>
        </div>
      ) : null}
      {msg ? (
        <p role={msg.kind === "error" ? "alert" : "status"} data-testid={msg.kind === "ok" ? "form-ok" : "form-error"} className={`text-sm ${msg.kind === "ok" ? "text-[var(--success)]" : "text-[var(--danger)]"}`}>
          {msg.text}
        </p>
      ) : null}
    </div>
  );
}
